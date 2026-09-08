# Testing Approach, Real Bugs Found, Limitations and Trade-offs

## 1. Testing approach

Three layers, all real (no mocked-out "pretend this passed"):

1. **Unit + integration tests, `service/`** -- 37 tests (`scripts/test.sh service` reports 42,
   since it also runs the 5 `common/` JSON tests below): pure-logic
   unit tests for `Base62Encoder`, `UrlValidator` (including the SSRF guard), `AliasValidator`,
   `RateLimiter`, and `InMemoryUrlStore` (including a WAL-replay-after-restart test that writes
   through a real `WriteAheadLog`, discards the in-memory store, and rebuilds it from disk), plus
   `UrlShortenerIntegrationTest`, which starts a real `HttpServer` on port 0 via `Bootstrap.start()`
   and drives it with `java.net.http.HttpClient`: full create/redirect/analytics/delete lifecycle,
   404/409/400 error paths, a redirect-after-delete 410, and a rate-limit burst test that asserts a
   mix of 200s and 429s. `ExpiredUrlsFeatureTest` (added by the brownfield scenario, see below) is
   part of this suite going forward.
2. **Unit tests, `orchestrator/`** -- 15 tests (`scripts/test.sh orchestrator` reports 20, since
   it also runs the same 5 `common/` JSON tests):
   `WorkflowGraphTest` (batching, linear chains, unknown-dependency rejection, duplicate-id
   rejection, cycle detection, `allStages` ordering) and `OrchestratorTest`, which builds small
   synthetic graphs with fake agents to exercise every reliability mechanism in isolation and
   under the tester's control: a full happy path, retry-recovers-from-transient-failure,
   fallback-after-retries-exhausted, rollback-deletes-what-was-written,
   dependent-stage-skipped-when-its-upstream-fails, approval-rejection-fails-the-stage,
   safe-stop-skips-a-later-batch-after-a-critical-guardrail-violation, and two re-planning tests
   (unchanged input is reused on a second run; a changed input cascades staleness to a dependent
   whose own params didn't change).
3. **System-level validation: the three real scenario runs** (`scripts/run-orchestrator.sh
   greenfield|brownfield|ambiguous`). These are not simulations of the orchestrator -- they run
   the actual engine, against the actual `service/` codebase, writing actual files under
   `runs/<scenario>/` (audit log, metrics, artifacts, backups) and, for brownfield and ambiguous,
   actually modifying `service/` source and re-running the full test suite as part of the
   `testing` stage. `scripts/test.sh all` (57 tests) is the state of the suite after all three
   scenarios have run, in the repo as committed.

Total: 57 tests (37 service + 5 common + 15 orchestrator) via `scripts/test.sh all`, plus 3
end-to-end scenario runs, all green as committed.

## 2. Two real bugs found by actually running this system

Both of these were found by running the software, not by static reasoning about it -- which is
the whole argument for building a runnable prototype instead of only a design document.

### Bug #1: HTTP routing prefix-collision

**Symptom:** `UrlShortenerIntegrationTest#rateLimitTripsAfterCapacityExceeded` failed
intermittently -- `sawRateLimited==0` when the test expected at least one `429`. The test's probe
paths were `/healthz-check-0`, `/healthz-check-1`, etc.

**Root cause:** `com.sun.net.httpserver.HttpServer` matches registered contexts by **string
prefix**, not by path segment. `/healthz-check-0` starts with the string `/healthz`, so it was
routed to `HealthHandler` -- which sits outside `RateLimitFilter` -- instead of hitting the rate
limiter at all. This is a real, easy-to-reintroduce bug class in this HTTP layer: any handler
registered at a short path (`/healthz`, `/metrics`, `/api/v1/urls/expired` if it were registered
as its own context) can silently shadow any longer path that happens to share its prefix,
including a legitimate short code that starts with the same characters.

**Fix (three parts, all real, all still in the codebase):**
- `AliasValidator`'s reserved-word check changed from exact-equals to prefix matching
  (`RESERVED_PREFIXES`), so a custom alias can never be created that collides with a reserved
  context prefix in the first place.
- `HealthHandler` and `MetricsHandler` both added an exact-path check
  (`!"/healthz".equals(exchange.getRequestURI().getPath())`) as defense-in-depth, returning 404
  for anything that merely starts with the registered prefix.
- The integration test's probe paths were changed to a non-colliding prefix (`/rl-probe-{i}`), and
  a permanent regression test, `AliasValidatorTest#rejectsAliasesThatWouldBeShadowedByAReservedPathPrefix`,
  was added.

This bug class is also why the brownfield scenario's `GET /api/v1/urls/expired` endpoint (see
`docs/scenarios/02-brownfield.md`) was deliberately implemented as a branch **inside** the
existing `/api/v1/urls/{code}` handler rather than as its own `HttpServer` context -- registering
it separately would have reintroduced exactly this problem for any short code named `expired`.

### Bug #2: numeric-type instability in the re-planning content hash

**Symptom:** running the greenfield scenario twice in a row with nothing changed should reuse
every stage (that's the entire point of the content-hash re-planning mechanism). On the second
run, 5 of 7 stages correctly showed `REUSED` -- but `testing` and `release` showed `STALE` and
re-executed, for no visible reason. Found by literally running the scenario twice and reading the
output, not by a pre-written test.

**Root cause:** `RunStateStore` persists each stage's output as JSON and reconstructs it on the
next run via `Json.parseObject`, and this hand-rolled parser decodes every JSON number as a
`Double` (see `common/src/main/java/com/schwab/common/json/Json.java`). A freshly-executed agent's
`StageOutput.data()`, by contrast, contains whatever native Java type the agent put there --
`Integer` in several agents (e.g. `DesignAgent` putting an `int` count of impacted files).
`Orchestrator.computeInputHash()` hashes `Json.write(...)` of each dependency's output data to
decide whether a stage's inputs changed. `Json.write(10)` (a native `Integer`) produces the text
`"10"`; `Json.write(10.0)` (the same value after a JSON round-trip, now a `Double`) produces
`"10.0"`. Two representations of the identical value hash differently -- so any stage whose
dependency had been `REUSED` (and was therefore JSON-reconstructed) computed a different input
hash than the same stage would when its dependency had just freshly `COMPLETED` (still holding
native types), even though nothing about the actual data changed.

**Fix:** in `Orchestrator.executeStage()`, immediately after a stage's primary or fallback
execution succeeds -- before that output is used for guardrails, context, or hashing -- its data
is canonicalized through the same JSON round-trip every persisted output goes through:
`output = new StageOutput(Json.parseObject(Json.write(output.data())), output.artifacts(),
output.summary())`. This guarantees a stage's output has the same shape regardless of whether it
just ran or was reconstructed from disk, so the hash is stable. Verified by rerunning greenfield
twice after the fix: the second run showed all 7 stages `REUSED` (`docs/scenarios/01-greenfield.md`,
§5). Two regression tests were added to `OrchestratorTest`:
`unchangedInputIsReusedOnASecondRun` and `changedInputCausesStaleReExecutionAndCascadesToDependents`
(the latter also checks that an upstream change correctly cascades `STALE` to a downstream stage
whose own params never changed, which the first version of the fix could easily have gotten
right for the "nothing changed" case while still being wrong for the "something changed" case).

A related, smaller issue found while fixing this: `RunMetrics.successRate` was computed as
`completedStages / totalStages`, which reported `0%` for a rerun where all 7 stages legitimately
short-circuited to `REUSED` -- a 100%-successful outcome via the fast path, reported as a total
failure. Changed to `(completedStages + reusedStages) / totalStages`.

## 3. Known limitations

- **WAL is never compacted.** `WriteAheadLog` appends forever; `InMemoryUrlStore` replays the
  entire file on startup. Fine for a prototype and for this assessment's runtime; a long-lived
  production instance would need periodic snapshot+truncate.
- **Single-instance, in-memory store.** `InMemoryUrlStore` is a `ConcurrentHashMap`; there is no
  clustering, sharding, or external database. Horizontal scaling would require moving state out
  of the process (e.g. into a real datastore) and is out of scope here.
- **Rate limiting is in-process only.** `RateLimiter`'s token buckets live in one JVM's heap; they
  provide no protection if the service is run behind a load balancer with multiple instances.
- **No TLS, no authentication/authorization.** The HTTP layer is `com.sun.net.httpserver` with no
  auth filter; anyone who can reach the port can create, read, or delete short URLs. Acceptable
  for a local assessment prototype, not for anything internet-facing.
- **The hand-rolled JSON library is not RFC 8259-complete.** It has no support for JSON
  exponent-only number edge cases beyond what `Double.parseDouble` accepts, decodes all numbers
  as `Double` (the direct cause of bug #2 above, now worked around rather than eliminated at the
  source), and its error messages are minimal. It was written specifically to avoid depending on
  Jackson/Gson, which brings the trade-off discussed below.
- **The orchestrator's agents are deterministic, rule-based Java, not LLM calls.**
  `RequirementsAgent`'s "domain vocabulary" match and `DesignAgent`'s keyword-in-file-content scan
  are heuristics, not language understanding -- they're intentionally simple and fully
  inspectable/testable, at the cost of not generalizing to a requirement phrased in genuinely novel
  vocabulary. See the design-rationale note below for why this trade-off was made deliberately.
- **Retry/rollback/fallback triggers in the demo scenarios are simulated** (`simulateTransientFailureUntilAttempt`,
  `alwaysFail`, `selfCheckShouldFail` stage params) rather than emerging from genuinely flaky
  external systems, so that the three required scenarios are reproducible on every run rather than
  dependent on real infrastructure cooperating on demand. The mechanisms themselves (retry loop,
  fallback invocation, rollback-via-backup-and-restore, safe-stop) are exercised for real by these
  triggers -- only the *cause* of the failure is synthetic, not the response to it.
- **Approval gates are file-backed for reproducibility**, not connected to a real
  identity-and-approval system. `FileBackedApprovalGate` reads a pre-recorded decision from
  `approvals/<scenario>.json`; `ConsoleApprovalGate` (used when no file is present and `--auto`
  isn't passed) prompts on stdin for a real interactive run. Neither integrates with an actual
  SSO/ticketing system, which a production version would need.

## 4. Trade-offs and assumptions

- **Zero third-party dependencies (no Maven-fetched libraries at all), including no Spring Boot
  and no Jackson/Gson**, was a deliberate pivot forced by the sandboxed build environment this was
  developed in: Maven Central is unreachable behind this environment's egress policy (confirmed via
  a direct `curl` returning 403 from the proxy, and via a trivial zero-dependency Maven project
  failing because even `maven-resources-plugin` itself can't be resolved). Rather than treat that
  as a blocker, it was treated as a realistic constraint -- many regulated environments (this
  assessment is from a bank) restrict outbound network access from build systems -- and the whole
  system was built on pure JDK 21 (`com.sun.net.httpserver`, hand-rolled JSON, a ~150-line
  reflection-based test runner instead of JUnit) so that `scripts/build.sh` and `scripts/test.sh`
  work with no internet access and no package manager at all, from a fresh clone. The explicit
  assumption this trade-off makes: the reviewer's environment can run `javac`/`java` (JDK 21+)
  without needing network access to a build tool, which is a strictly weaker requirement than a
  Maven/Gradle-based project would impose.
- **Assumption:** "the assignment PDF asked for Java (Spring Boot)" was the user's own stack
  choice; Spring Boot itself was not used, for the reason above. The service is still a normal
  layered Java HTTP application (handlers / validation / store / metrics), so the shape of the
  code should read as familiar to anyone expecting a Spring-style service, just wired by hand
  instead of by a DI container.
- **Assumption:** the orchestrator's "agents" are expected to be real, inspectable, testable Java
  code that performs genuine work against the genuine codebase (scanning real files, running the
  real test suite, writing real artifacts, reverting real file writes) rather than an LLM prompted
  to *pretend* to do SDLC work. This was a deliberate reading of the assignment's emphasis on
  "working runnable prototype" and "audit-grade observability" -- a system whose audit log records
  what an LLM *said* it did is weaker evidence than one whose audit log records what deterministic
  code actually did, and the latter is also what makes the three scenario runs fully reproducible
  for a reviewer.
- **Trade-off:** this reproducibility comes at the cost of the agents not generalizing to
  arbitrary natural-language requirements the way an LLM-backed agent would. `RequirementsAgent`
  would not correctly classify a requirement phrased with none of its known vocabulary. This is an
  intentional scope boundary, not an oversight: the assessment's hardest-to-satisfy requirements
  (dependency graph with gates, parallel execution with synchronization, bounded retry/fallback/
  rollback, policy guardrails, audit-grade lineage, dynamic re-planning) are all properties of the
  *engine*, not of any individual agent's implementation strategy, and the engine is fully
  decoupled from how each agent decides what to do -- an LLM-backed `RequirementsAgent` could be
  substituted in without changing `Orchestrator`, `WorkflowGraph`, or any governance code.
