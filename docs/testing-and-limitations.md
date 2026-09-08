# Testing Approach, Real Bugs Found, Limitations and Trade-offs

## 1. Testing approach

Three layers, all real (no mocked-out "pretend this passed"):

1. **Unit + integration tests, `service/`** (JUnit 5, via Maven/Surefire -- `mvn -f service/pom.xml
   test`, or `scripts/test.sh service`): pure-logic unit tests for `Base62Encoder`, `UrlValidator`
   (including the SSRF guard), `AliasValidator`, `RateLimiter`, and `InMemoryUrlStore` (including a
   WAL-replay-after-restart test that writes through a real `WriteAheadLog`, discards the
   in-memory store, and rebuilds it from disk), plus `@SpringBootTest`-based integration tests
   (`UrlShortenerIntegrationTest`, `RateLimitIntegrationTest`) that start the real Spring Boot
   application on a random port and drive it with `java.net.http.HttpClient`: full
   create/redirect/analytics/delete lifecycle, 404/409/400 error paths, a redirect-after-delete
   410, and a rate-limit burst test that asserts a mix of 200s and 429s.
   `ExpiredUrlsFeatureTest` (added by the brownfield scenario, see below) is part of this suite
   going forward.
2. **Unit tests, `orchestrator/`** (hand-rolled `com.schwab.testlib.TestRunner`, via
   `scripts/test.sh orchestrator`): `WorkflowGraphTest` (batching, linear chains,
   unknown-dependency rejection, duplicate-id rejection, cycle detection, `allStages` ordering) and
   `OrchestratorTest`, which builds small synthetic graphs with fake agents to exercise every
   reliability mechanism in isolation and under the tester's control: a full happy path,
   retry-recovers-from-transient-failure, fallback-after-retries-exhausted,
   rollback-deletes-what-was-written, dependent-stage-skipped-when-its-upstream-fails,
   approval-rejection-fails-the-stage, safe-stop-skips-a-later-batch-after-a-critical-guardrail-violation,
   and two re-planning tests (unchanged input is reused on a second run; a changed input cascades
   staleness to a dependent whose own params didn't change).
3. **System-level validation: the three real scenario runs** (`scripts/run-orchestrator.sh
   greenfield|brownfield|ambiguous`). These are not simulations of the orchestrator -- they run
   the actual engine, against the actual `service/` codebase, writing actual files under
   `runs/<scenario>/` (audit log, metrics, artifacts, backups) and, for brownfield and ambiguous,
   actually modifying `service/` source and re-running the full test suite as part of the
   `testing` stage (`scripts/test.sh service`, which shells out to Maven).

`scripts/test.sh all` runs all of the above (orchestrator's hand-rolled runner plus service's
Maven/JUnit 5 suite) and writes one combined JSON summary, so `TestingAgent` needs no
scope-specific logic to consume either module's results.

## 2. Two real bugs found by actually running this system

Both of these were found by running the software, not by static reasoning about it -- which is
the whole argument for building a runnable prototype instead of only a design document. The first
was found against an earlier, zero-dependency version of `service/` (see §4 for why that version
existed) and is kept here as real project history, since the fix it produced --
`AliasValidator`'s reserved-word protection -- is still load-bearing today, just under a
different (and narrower) routing-collision risk once the service moved to Spring Boot.

### Bug #1 (historical): HTTP routing prefix-collision

**Symptom:** an integration test asserting a rate-limit burst failed intermittently --
`sawRateLimited==0` when the test expected at least one `429`. The test's probe paths were
`/healthz-check-0`, `/healthz-check-1`, etc.

**Root cause:** the service's HTTP layer at the time was `com.sun.net.httpserver.HttpServer`,
which matches registered contexts by **string prefix**, not by path segment. `/healthz-check-0`
starts with the string `/healthz`, so it was routed to the health handler -- which sat outside the
rate-limit filter -- instead of hitting the rate limiter at all. Any handler registered at a short
path (`/healthz`, `/metrics`) could silently shadow any longer path that happened to share its
prefix, including a legitimate short code that started with the same characters.

**Fix at the time:** `AliasValidator`'s reserved-word check became a *prefix* match (any alias
starting with a reserved word was rejected), plus exact-path defense-in-depth checks in the
health/metrics handlers, plus a regression test and a non-colliding probe-path fix.

**What changed when the service moved to Spring Boot:** Spring MVC's request mapping matches
routes by exact path-segment comparison, with a literal mapping preferred over a `{variable}`
pattern only on an *exact* match -- it has no raw string-prefix matching at all, so this entire
bug class does not exist under Spring's router. `AliasValidatorTest#acceptsAliasesThatMerelyStartWithAReservedWord`
is a direct regression test proving `healthzone`, `apidocs`, etc. are valid aliases today (they
were rejected under the old HTTP layer). `AliasValidator.RESERVED_EXACT` was narrowed from
prefix-match to exact-match accordingly -- see its javadoc and `docs/architecture.md` §2.6 for the
full before/after. The narrower risk that remains under Spring -- a code *exactly* equal to a
reserved literal path segment being permanently shadowed -- is why the reserved-word check still
exists at all, just doing less work than it used to.

### Bug #2: numeric-type instability in the orchestrator's re-planning content hash

This bug lives entirely in `orchestrator/`, which was unaffected by the service's move to Spring
Boot, so this section is unchanged real project history.

**Symptom:** running the greenfield scenario twice in a row with nothing changed should reuse
every stage (that's the entire point of the content-hash re-planning mechanism). On the second
run, 5 of 7 stages correctly showed `REUSED` -- but `testing` and `release` showed `STALE` and
re-executed, for no visible reason. Found by literally running the scenario twice and reading the
output, not by a pre-written test.

**Root cause:** `RunStateStore` persists each stage's output as JSON and reconstructs it on the
next run via `Json.parseObject`, and this hand-rolled parser (`common/`, used by the orchestrator)
decodes every JSON number as a `Double`. A freshly-executed agent's `StageOutput.data()`, by
contrast, contains whatever native Java type the agent put there -- `Integer` in several agents
(e.g. `DesignAgent` putting an `int` count of impacted files). `Orchestrator.computeInputHash()`
hashes `Json.write(...)` of each dependency's output data to decide whether a stage's inputs
changed. `Json.write(10)` (a native `Integer`) produces the text `"10"`; `Json.write(10.0)` (the
same value after a JSON round-trip, now a `Double`) produces `"10.0"`. Two representations of the
identical value hash differently -- so any stage whose dependency had been `REUSED` (and was
therefore JSON-reconstructed) computed a different input hash than the same stage would when its
dependency had just freshly `COMPLETED` (still holding native types), even though nothing about
the actual data changed.

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
- **No TLS, no authentication/authorization.** Spring Security is not on the classpath and no auth
  filter is configured; anyone who can reach the port can create, read, or delete short URLs.
  Acceptable for a local assessment prototype, not for anything internet-facing -- adding
  `spring-boot-starter-security` would be the natural next step.
- **The orchestrator's hand-rolled JSON library (`common/`) is not RFC 8259-complete.** It has no
  support for JSON exponent-only number edge cases beyond what `Double.parseDouble` accepts,
  decodes all numbers as `Double` (the direct cause of bug #2 above, now worked around rather than
  eliminated at the source), and its error messages are minimal. It is used only by the
  orchestrator (audit log, `state.json`) -- `service/` uses Jackson (bundled with
  `spring-boot-starter-web`), which does not have this limitation.
- **The orchestrator's agents are deterministic, rule-based Java, not LLM calls.**
  `RequirementsAgent`'s "domain vocabulary" match and `DesignAgent`'s keyword-in-file-content scan
  are heuristics, not language understanding -- they're intentionally simple and fully
  inspectable/testable, at the cost of not generalizing to a requirement phrased in genuinely novel
  vocabulary. See §4 for why this trade-off was made deliberately.
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
- **`scripts/build.sh`/`scripts/test.sh` now require two toolchains** (`javac` for
  `orchestrator`/`common`/`testlib`, Maven for `service/`) rather than one -- see §4 for why that
  split exists and isn't expected to be a real obstacle for a reviewer's machine.

## 4. Trade-offs and assumptions

- **Why `orchestrator/`, `common/` and `testlib/` are zero-dependency plain Java, and why
  `service/` isn't (anymore).** This project was originally developed end-to-end in a sandboxed
  environment with no route to Maven Central (confirmed directly: a `curl` to
  `repo.maven.apache.org` returned a `403` from the environment's egress proxy, and even a
  trivial zero-dependency Maven project failed because `maven-resources-plugin` itself couldn't be
  resolved). Rather than block on that, the whole system -- `service/` included -- was first built
  on pure JDK 21 (`com.sun.net.httpserver`, hand-rolled JSON, a ~150-line reflection-based test
  runner instead of JUnit), so it would build and test with no internet access and no package
  manager at all. Once development moved to an environment with normal internet access (a
  developer's own machine), `service/` was rebuilt on **Spring Boot** -- the stack actually
  requested for this assessment -- while `orchestrator/`, `common/` and `testlib/` were kept
  dependency-free, since they never needed a framework in the first place and keeping them that
  way costs nothing. The result is a deliberate two-toolchain build (`javac` for the orchestration
  engine, Maven for the service), which is documented rather than hidden: `scripts/build.sh` and
  `scripts/test.sh` drive both, and README.md states the Maven prerequisite up front.
- **Assumption:** a reviewer's machine has normal outbound internet access to Maven Central (the
  overwhelming common case for a developer laptop, unlike this project's original build sandbox).
  If that assumption is wrong, `orchestrator/`, `common/` and `testlib/` still build and test with
  no network access at all (`scripts/test.sh orchestrator`); only `service/`'s Spring Boot build
  needs it, and only once (Maven caches downloaded dependencies locally after the first build).
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
