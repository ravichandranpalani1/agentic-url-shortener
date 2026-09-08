# Architecture

## 1. Two programs, one repo

```
                      ┌─────────────────────────────┐
                      │        orchestrator/         │
                      │  agentic SDLC engine (CLI)   │
                      │                               │
                      │  reads/writes real files in   │
                      │  the repo, runs real tests    │
                      └───────────────┬───────────────┘
                                      │ operates on
                                      ▼
                      ┌─────────────────────────────┐
                      │          service/             │
                      │   URL shortener (HTTP API)    │
                      └─────────────────────────────┘
                      ┌─────────────────────────────┐
                      │   common/ (JSON) + testlib/   │
                      │   shared by both programs      │
                      └─────────────────────────────┘
```

The orchestrator is not a wrapper around the service, and it isn't a library the service
imports at runtime -- it's a separate build-time/dev-time tool that treats the service's
source tree as its object of work: it reads it (`DesignAgent`'s impact scan), writes to it
(`ImplementationAgent` applying a change), and shells out to build/test it
(`TestingAgent` invoking `scripts/test.sh service`). This mirrors how a real CI/CD or
agentic coding tool relates to the codebase it operates on: a separate process with
filesystem and process-execution access, not a compiled-in dependency.

## 2. The service

### 2.1 Component map

```
UrlShortenerApplication (@SpringBootApplication main)
  └── AppConfig (@Configuration -- Spring's wiring point, analogous to a hand-written Bootstrap)
        ├── WriteAheadLog            (data/wal.log -- durability)
        ├── InMemoryUrlStore          (implements UrlStore)
        ├── ServiceMetrics            (@Component, in-process counters)
        ├── RateLimiter               (per-client token buckets)
        └── Spring MVC (embedded Tomcat)
              ├── /api/v1/urls          -> UrlsController         (POST create, GET list)
              ├── /api/v1/urls/{code}   -> UrlItemController       (GET info, GET .../analytics, DELETE)
              ├── /api/v1/urls/expired  -> UrlItemController*      (GET -- added by the brownfield scenario)
              ├── /healthz              -> HealthController
              ├── /metrics              -> MetricsController
              └── /{code}, /            -> RedirectController      (GET -> 302, async click log)
              (ServiceGovernanceFilter wraps every request except /healthz and /metrics: rate
               limiting + per-request reliability metrics, in one servlet Filter)
```
`*` added by the brownfield scenario -- see `docs/scenarios/02-brownfield.md`.

`AppConfig` is the single wiring point for the service's plain-Java domain objects (the
store, the WAL, the rate limiter); Spring's `@SpringBootTest` starts the exact same object
graph on a random port for `UrlShortenerIntegrationTest` and friends. There is no separate
"test wiring" -- tests exercise the exact object graph production uses.

This service moved from a hand-rolled, zero-dependency HTTP layer
(`com.sun.net.httpserver.HttpServer`) to Spring Boot -- see
`docs/testing-and-limitations.md` §4 for why the zero-dependency version existed at all
(a sandboxed build environment with no Maven Central access) and what changed once that
constraint no longer applied to the environment actually building this code.

### 2.2 Request flow: create

`POST /api/v1/urls` (`UrlsController`) -> `UrlValidator.validate` (scheme/host/SSRF
checks) -> `AliasValidator.validate` (if a custom alias was supplied) ->
`InMemoryUrlStore.create`, which either uses the caller's alias verbatim or generates one
from an internal `AtomicLong` sequence via `Base62Encoder`, checking for collisions before
committing -> the mutation is appended to the write-ahead log *before* the in-memory map is
updated, so a crash between those two steps still leaves a replayable, consistent log.

### 2.3 Request flow: redirect

`GET /{code}` (`RedirectController`) is intentionally the leanest path in the service: look up
the record, check `active`/`isExpired`, write the `Location` header and a bare 302 -- and
only *after* the response has been sent does it submit a click-analytics write to a
background executor. Analytics recording can never add latency to a redirect, and a
failure inside the analytics write is caught and logged, never allowed to affect a client
who has already received their redirect. This asymmetry (redirect path minimal and
synchronous, analytics path best-effort and asynchronous) is the service's central
reliability design decision, and it's explicitly what the ambiguous scenario's requirement
clarification had to reason about when asked to make analytics "more reliable" without
regressing it (`docs/scenarios/03-ambiguous.md`).

### 2.4 Durability: write-ahead log + replay

There is no external database. `WriteAheadLog` appends one JSON line per mutation
(`CREATE`, `DELETE`, `CLICK`) to `data/wal.log`; `InMemoryUrlStore`'s constructor replays
every line on startup to rebuild its `ConcurrentHashMap` state, including the id sequence
(so newly generated codes never collide with pre-restart ones) and click counts. This is
verified for real in `InMemoryUrlStoreTest#writeAheadLogSurvivesStoreRestart` (build one
store, mutate it, build a *second* store instance against the same log file, assert its
state matches) and was manually verified against a running server process during
development (create, click, delete, kill -9 the process, restart, confirm the deletion and
click count both survived).

Trade-off: the log is never compacted, so its size grows without bound relative to the
number of mutations, not the number of live records -- fine for a prototype's lifetime, a
real deployment would need periodic snapshot+truncate. This is called out again in
`docs/testing-and-limitations.md`.

### 2.5 Reliability features

- **Rate limiting** (`rate/`): a hand-rolled token bucket per client key (X-Forwarded-For
  or socket address), refilling continuously based on elapsed wall-clock time rather than a
  background thread, with idle-bucket eviction so the tracked-client map doesn't grow
  unbounded. Applied to every context except `/healthz` and `/metrics`, so rate-limited
  clients can still be health-checked.
- **SSRF guard** (`validation/UrlValidator`): rejects `longUrl`s that resolve to loopback,
  link-local or private-network addresses -- a public redirect service that will happily
  point at `http://169.254.169.254/...` or `http://localhost:8080/admin` is a known attack
  vector.
- **Soft delete + TTL expiry**: deleted/expired records return `410 Gone` rather than
  disappearing outright, and are excluded from redirects but retained for audit purposes.
- **`/healthz` and `/metrics`**: liveness plus in-process counters (request count, error
  rate, rate-limited count, redirect count, average latency, stored-URL count).

### 2.6 A routing bug this design surfaced -- and how moving to Spring Boot changed it

The original, zero-dependency version of this service used
`com.sun.net.httpserver.HttpServer`, which matches registered contexts by **string
prefix**, not by path segment: a context registered at `/healthz` also matched a request
for `/healthz-anything`. That bug was caught for real by a failing integration test (see
`docs/testing-and-limitations.md` for the full story) and was guarded against with a
reserved-*prefix* check in `AliasValidator` plus defense-in-depth exact-path checks in the
health/metrics handlers.

Spring MVC's request mapping does not have that bug class: routes are matched by exact
path-segment comparison (with literal mappings preferred over `{variable}` patterns on an
exact match), so a code merely *starting with* a reserved word -- `healthzone`,
`apidocs` -- is perfectly reachable and no longer needs to be rejected.
`AliasValidator.RESERVED_EXACT` was narrowed from prefix-match to exact-match accordingly;
`AliasValidatorTest#acceptsAliasesThatMerelyStartWithAReservedWord` is a direct regression
test proving those aliases are now valid. The remaining, narrower risk -- a code *exactly*
equal to a reserved literal segment (e.g. `expired`, once the brownfield scenario adds
`GET /api/v1/urls/expired`) would be permanently shadowed and unreachable via GET -- is
still real under Spring's routing too, so `AliasValidator` still reserves those exact
words. See `UrlItemController`'s javadoc for the brownfield-specific case.

## 3. The orchestration engine

### 3.1 Why a custom engine instead of a workflow library

Everything here needed to run inside a build environment this assessment turned out to
have **no access to Maven Central or any other package registry** (see
`docs/testing-and-limitations.md`), which ruled out adopting an existing workflow engine
(Temporal, Airflow, Spring State Machine, ...) even for prototyping. The engine below is
therefore hand-rolled, but its shape follows standard workflow-engine concepts (DAG,
batched/topological execution, gates, bounded retries) precisely so the design would
transfer to a real engine if the dependency constraint were lifted -- see
`docs/engineering-summary.md` for that trade-off spelled out explicitly.

### 3.2 Model

| Type | Role |
|---|---|
| `Stage` | Immutable definition: id, dependencies, the `Agent` to run, retry/backoff config, optional approval requirement, optional fallback/rollback agents, guardrails, static params. Built with a fluent `Builder`. |
| `WorkflowGraph` | Validates the stage set (no unknown dependencies, no cycles) and computes topologically-sorted execution **batches** -- each batch is a set of stages with no dependency on each other, safe to run in parallel; batches themselves run in sequence. |
| `WorkflowContext` | Cross-stage state for one run: a shared key/value bag, every completed stage's `StageOutput`, and an append-only **decision lineage** (who decided what, and why, at every gate/replan/fallback/rollback). |
| `StageInput` / `StageOutput` | What an `Agent` receives (its own static params + the shared context + which attempt number this is) and returns (a data map for downstream stages, a list of artifact file paths it wrote, a human-readable summary). |
| `StageStatus` | `PENDING → RUNNING → {COMPLETED, REUSED, FAILED, ROLLED_BACK, SKIPPED}`, plus transient `RETRYING` / `BLOCKED_ON_APPROVAL` / `STALE` markers surfaced in the audit log. |

### 3.3 Engine: one stage's execution, in order

```
1. Dependency check       -- any dependency not COMPLETED/REUSED => SKIPPED, stop
2. Safe-stop check         -- a prior critical guardrail violation this run => SKIPPED, stop
3. Re-planning check        -- compute this stage's input hash (its static params + the
                                output data of every stage it depends on, sorted for
                                determinism); if it matches the prior run's recorded hash
                                AND that run COMPLETED, mark REUSED and carry the prior
                                output forward without re-executing. Otherwise mark STALE
                                (if there *was* a prior run) and continue.
4. Pre-execution guardrails -- e.g. RestrictedPathGuardrail; a violation fails the stage
                                (and, if flagged safeStop, halts the rest of the run)
5. Approval gate            -- if required, block on ApprovalGate.requestApproval(); a
                                rejection fails the stage
6. Retry loop                -- up to maxRetries attempts, exponential-ish backoff between
                                attempts, each failure logged
7. Fallback                  -- if all attempts failed and a fallback agent is configured,
                                run it; its success completes the stage
8. Rollback                  -- if there is still no output and a rollback agent is
                                configured, run it (best-effort) to undo this stage's
                                partial side effects, then fail the stage
9. Post-execution guardrails -- e.g. SecretScanGuardrail scanning the artifacts this stage
                                just wrote; a violation fails the stage post hoc
10. Record + persist          -- output canonicalized (see 3.5), stored in the shared
                                context, persisted to state.json, logged as COMPLETED
```

Batches execute with a full synchronization barrier between them (every stage in batch N
finishes -- successfully, failed, or skipped -- before batch N+1 starts), so a stage can
never observe a dependency's output mid-write. Within a batch, stages run concurrently on a
fixed thread pool.

### 3.4 Governance

- **`ApprovalGate`**: `ConsoleApprovalGate` (blocks on stdin, for a real human at the
  keyboard), `FileBackedApprovalGate` (reads pre-recorded decisions from
  `approvals/<scenario>.json` -- stands in for a real approval-system-of-record and is what
  the three shipped scenarios use, so runs are reproducible), and `AutoApproveGate`
  (auto-approves everything, every decision explicitly flagged `simulated=true` in the
  audit log so it can never be mistaken for a real approval; smoke-testing only).
- **`PolicyGuardrail`**: `checkBefore`/`checkAfter` hooks around every stage.
  `RestrictedPathGuardrail` blocks an implementation stage from declaring a target file
  under the orchestrator's own source or `.git/` -- approving a feature change is not the
  same as authorizing an agent to rewrite its own control plane. `SecretScanGuardrail`
  scans every artifact a stage claims to have written for credential-shaped patterns
  (AWS keys, `password=`/`api_key=`-style assignments, PEM private key headers); a match
  trips `safeStop=true`, halting the entire run rather than just failing one stage.
- **Safe-stop**: an `AtomicBoolean` checked before every stage starts and before every new
  batch; once a critical guardrail violation trips it, no further stage begins (an
  already-running stage in the same batch is allowed to finish).

### 3.5 Audit, metrics, and a bug that running the system for real surfaced

`AuditLogger` appends one JSON line per event (`STARTED`, `RETRY`, `APPROVAL_REQUESTED`,
`APPROVED`/`REJECTED`, `GUARDRAIL_VIOLATION`, `ROLLED_BACK`, `COMPLETED`, ...) to
`runs/<scenario>/audit.jsonl`, all sharing one `traceId` per run. `RunMetrics` derives
success rate, retry/rollback counts, mean-time-to-recovery (average time between a stage's
first failure and its eventual success), and end-to-end run latency from those same events
as they happen -- see any `runs/<scenario>/audit.jsonl` for a real, complete example.

Stage outputs are **canonicalized through the same JSON serialization `RunStateStore` uses
for persistence** immediately after a successful execution, before they're stored in the
context or hashed for re-planning. This isn't cosmetic: a freshly-executed stage's output
map holds native Java types (e.g. an `Integer`), while a `REUSED` stage's output is
reconstructed from JSON on disk (where every number decodes as a `Double`) -- without
normalizing both to the same representation, `computeInputHash()` produced a different
string for semantically identical data depending on whether a dependency had just run or
been reused, which spuriously invalidated downstream stages. This was found by actually
running the greenfield scenario twice in a row, not by inspection -- the full story,
including the fix and its regression tests, is in
[`docs/testing-and-limitations.md`](testing-and-limitations.md).

### 3.6 Dynamic re-planning

Because a stage's input hash is a function of its own static params **plus the output data
of every stage it depends on**, a change anywhere upstream automatically invalidates every
downstream stage that transitively depends on it -- the engine needs no stage-specific
invalidation rules. `OrchestratorTest#changedInputCausesStaleReExecutionAndCascadesToDependents`
exercises exactly this: an upstream stage's param changes, only the upstream stage's own
diff triggers re-execution, and that alone is enough to cascade staleness to a downstream
stage whose own params never changed. The real scenario runs demonstrate the same
mechanism at full scale: running `greenfield` twice with nothing changed reused all 7
stages (state persisted in `runs/greenfield/state.json`); the first bug in section 3.5 was
found precisely because that second run's re-execution pattern looked wrong.

### 3.7 Agents

| Agent | What it actually does |
|---|---|
| `RequirementsAgent` | Writes the raw requirement to disk verbatim, matches it against a fixed domain vocabulary to extract keywords, flags likely ambiguity via a vague-term/no-numeric-target heuristic, writes a normalized spec artifact. |
| `ClarificationAgent` | Packages a human-chosen interpretation (from the approval gate's rationale) into the context for downstream stages -- used only in the ambiguous scenario. |
| `DesignAgent` | Walks the real `service/src/main/java` tree and reports which files textually match the requirement's keywords -- a genuine (if simple) filesystem-based impact scan, not a canned answer. |
| `ImplementationAgent` | Either verifies a greenfield scaffold's expected files exist, or copies pre-drafted patch file(s) from `scenario-assets/` onto their real target paths, snapshotting any pre-existing content first so a paired rollback agent can restore it. |
| `RevertFileAgent` | Restores each file `ImplementationAgent` backed up, or deletes it if there was no backup (i.e. it was newly created). |
| `TestingAgent` | Shells out to `scripts/test.sh service`, parses the real JSON summary, fails the stage on any failing test. |
| `NotifyDownstreamAgent` / `QueueForManualFollowUpAgent` | A primary/fallback pair simulating a downstream notification and its graceful degradation. |
| `DocsAgent` | Generates `runs/<scenario>/artifacts/run-report.md` from the actual context: every stage's real summary and the full decision lineage. |
| `ReleaseReadinessAgent` | A defense-in-depth go/no-go check (the graph's dependency structure already prevents it from running if testing failed or a guardrail tripped, so a NO-GO here would mean the graph's own gating missed something). |

## 4. Key decisions, summarized

| Decision | Rationale |
|---|---|
| `service/` on Spring Boot; `orchestrator/`+`common/`+`testlib/` zero-dependency | The service is a normal Spring Boot application (Spring MVC, Jackson, JUnit 5/Surefire via Maven) built against the requested Java/Spring Boot stack. The orchestration engine and its shared libraries stay dependency-free plain JDK, both because they never needed a framework and because this project was developed in a sandbox with no Maven Central access -- see `docs/testing-and-limitations.md` for the full history of that constraint and how the two build tools (`javac` and `mvn`) now coexist in `scripts/build.sh`/`scripts/test.sh`. |
| Hand-rolled JSON (`common/`) | Used only by the orchestrator (audit log, `state.json`, WAL-format-adjacent data) where no framework is present; the service uses Jackson (bundled with `spring-boot-starter-web`) instead. |
| Hand-rolled test runner (`testlib/`) | Used only for `orchestrator/`'s own unit tests, which have no Spring/JUnit dependency to draw on; `service/` uses ordinary JUnit 5 via Maven Surefire. |
| WAL + replay instead of a database | Demonstrates a real, testable durability mechanism without adding a database dependency to a prototype; still true under Spring Boot. |
| Orchestrator agents operate on the real repo, not a sandbox copy | Produces genuine evidence (real diffs, real test runs) rather than a simulation; the trade-off is that running a scenario twice is only safe because `ImplementationAgent` snapshots before overwriting and the graph's re-planning makes an unchanged rerun a no-op. |
| Approvals are file-backed for the shipped scenarios | Makes the three required runs reproducible without a human at the keyboard, while keeping every decision auditable with a named approver and rationale. |
