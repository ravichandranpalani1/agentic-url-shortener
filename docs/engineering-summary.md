# Final Engineering Summary

## 1. Plan and rationale

The assignment has two distinct deliverables that are easy to conflate: a URL-shortener service,
and an agentic system that *builds and governs* software like that service across a full SDLC.
The plan was to treat the second as the actual assessment target and the first as its test
subject -- a real, non-trivial codebase for the orchestration layer to reason about, modify, and
validate, rather than a toy. Concretely, that meant:

1. Build `service/` first, as an ordinary well-tested Spring Boot HTTP application (the requested
   stack), so it would exist as real ground truth (real files, real tests, real bugs) for the
   orchestrator to operate on.
2. Build `orchestrator/` as a general-purpose engine -- a DAG of `Stage`s, each backed by an
   `Agent`, executed with dependency ordering, parallel batching, approval gates, retry/fallback/
   rollback, guardrails, audit logging, and content-hash re-planning -- with no scenario-specific
   logic baked into the engine itself. Every governance mechanism the assignment asks for lives in
   `Orchestrator`/`WorkflowGraph`/`governance/`/`audit/`, not in any individual agent. This part of
   the system stays zero-dependency plain Java (see below).
3. Write focused agents (`RequirementsAgent`, `DesignAgent`, `ImplementationAgent`, `TestingAgent`,
   `DocsAgent`, `ReleaseReadinessAgent`, plus fallback/rollback/clarification helpers) that do
   genuine work against the real `service/` tree, rather than agents that produce
   plausible-sounding narration.
4. Compose three scenario graphs (`ScenarioDefinitions`) from those same stages/agents, each
   structured so it naturally headlines a different reliability mechanism required by the
   assignment: greenfield headlines bounded retry, brownfield headlines fallback-after-exhausted-
   retries plus real codebase-impact reasoning, ambiguous headlines ambiguity detection, a human
   clarification gate between two real interpretations, and an isolated rollback.
5. Run all three for real against the real codebase, let two of them (brownfield, ambiguous)
   actually and permanently modify `service/`, and document exactly what happened using the real
   audit logs and metrics each run produced -- not reconstructed or idealized versions of them.

One development-environment constraint shaped the build tooling without changing the design:
early development happened in a sandbox with no route to Maven Central, which forced a temporary
pivot to a zero-dependency `service/` on plain JDK 21 so the whole system could be built and
tested at all. Once development continued on a machine with normal internet access, `service/`
was rebuilt on Spring Boot -- the stack actually requested for this assessment -- while
`orchestrator/`, `common/`, and `testlib/` (which never needed a framework) were kept
dependency-free. `docs/testing-and-limitations.md` §4 documents this history in full, including
the two-toolchain build (`javac` + Maven) it left behind, so it reads as a deliberate, disclosed
trade-off rather than an unexplained inconsistency.

## 2. Artifacts produced

- `service/` -- a Spring Boot / Maven application: create/redirect/analytics/delete/list APIs
  (`UrlsController`, `UrlItemController`, `RedirectController`), `Base62Encoder`, SSRF-guarding
  `UrlValidator`, `AliasValidator`, a token-bucket `RateLimiter` applied via a servlet
  `ServiceGovernanceFilter`, a WAL-backed `InMemoryUrlStore` with crash recovery, and
  `HealthController`/`MetricsController`.
- `orchestrator/` -- the engine (`WorkflowGraph`, `Orchestrator`, `RunStateStore`), governance
  primitives (2 guardrails, 3 approval gate implementations, `PolicyViolationException`), audit/
  metrics classes, 10 agents, and `ScenarioDefinitions` building the 3 required scenario graphs --
  all zero-dependency plain Java.
- `common/` and `testlib/` -- a dependency-free JSON library (used by the orchestrator's audit log
  and state persistence) and a ~150-line `@Test`-annotation reflection-based test runner (used by
  the orchestrator's own unit tests), both written specifically to keep that half of the system
  buildable with no network access.
- A full test suite: JUnit 5 (via Maven/Surefire) for `service/`, the hand-rolled runner for
  `orchestrator/`, all passing as committed -- see `scripts/test.sh all` for the combined run.
- Three scenario graphs (`runs/greenfield/`, `runs/brownfield/`, `runs/ambiguous/`) that populate
  with a real `audit.jsonl`, real metrics, and real generated artifacts (specs, design-impact
  notes, run reports, a manual-follow-up queue, a release-readiness verdict) every time they run.
- `scenario-assets/` and `approvals/` -- the pre-drafted patches `ImplementationAgent` applies for
  real, and the pre-recorded human approval decisions (with rationale) each scenario's approval
  gates consume.
- Documentation: `README.md`, `docs/architecture.md`, three scenario write-ups
  (`docs/scenarios/01-greenfield.md`, `02-brownfield.md`, `03-ambiguous.md`),
  `docs/testing-and-limitations.md`, and this document.
- `scripts/build.sh`, `scripts/test.sh`, `scripts/run-orchestrator.sh` -- the entire build/run
  surface, driving both toolchains (`javac` and Maven) from one command each.

## 3. Risks, trade-offs, and validation performed

The main trade-offs (the two-toolchain build, rule-based rather than LLM-backed agents, simulated
failure triggers for reproducibility, file-backed rather than SSO-integrated approvals) are laid
out in full in `docs/testing-and-limitations.md` §4, along with the known limitations (§3: no WAL
compaction, single-instance store, in-process-only rate limiting, no TLS/auth, an
RFC-8259-incomplete JSON library used only by the orchestrator). They are not repeated here in
full; the short version is that every one of them trades generality or production-readiness for
reproducibility and inspectability inside the constraints of this assessment, and each is scoped
so that swapping in the production-grade version (a real datastore, Spring Security, an
LLM-backed `RequirementsAgent`, a real approval/ticketing integration) would not require changing
the orchestration engine itself.

Validation performed, concretely:

- Full test suite green as committed -- `scripts/test.sh all` (Maven/JUnit 5 for `service/`, the
  hand-rolled runner for `orchestrator/`).
- All three scenario runs completed with a `release` decision of **GO**, with real metrics
  captured in each scenario's write-up (success rates, retry/rollback/approval counts, MTTR where
  applicable, end-to-end latency).
- Re-planning (dynamic re-execution vs. reuse) validated directly by rerunning the greenfield
  scenario twice and observing all 7 stages correctly short-circuit to `REUSED` the second time
  (`docs/scenarios/01-greenfield.md` §5) -- this is also how the numeric-type-fidelity bug (below)
  was actually found.
- Two real, non-hypothetical bugs were found by running the system rather than by inspection, and
  both were root-caused, fixed, and given permanent regression tests:
  1. An HTTP routing prefix-collision, found against an earlier zero-dependency HTTP layer, that
     could let a custom alias shadow a reserved endpoint (or vice versa) -- kept as documented
     history, since it is why `AliasValidator` reserves certain words at all; moving to Spring
     Boot's exact-path-segment routing eliminated that specific bug class (`docs/testing-and-limitations.md` §2).
  2. A numeric-type instability (`Integer` vs. JSON-round-tripped `Double`) in the orchestrator's
     content-hash re-planning check, which caused spurious `STALE` re-execution -- unaffected by
     the service's stack, still fully real.
  Full root-cause narrative for both: `docs/testing-and-limitations.md` §2.
- The brownfield and ambiguous scenarios' file changes are real, permanent modifications to
  `service/` -- verified by the fact that `GET /api/v1/urls/expired` and the hardened
  `RedirectController` exist in the committed source tree once those scenarios run, not just
  described in a run log, and by the full suite passing afterward with the new
  `ExpiredUrlsFeatureTest` included.

## 4. Assumptions

- The assignment's repeated emphasis on a "working runnable prototype" and "audit-grade
  observability/traceability" was read as a requirement that every mechanism (retry, fallback,
  rollback, guardrails, approval gates, re-planning) be demonstrated by actually executing code
  and inspecting its real output, not simulated in prose. This shaped essentially every design
  decision in this repo.
- "Java (Spring Boot)" (the requested stack) is what `service/` is built on. The orchestration
  engine itself -- the actual assessment target -- was kept dependency-free by design, since it is
  not the URL shortener and never needed a web framework; this is documented as an intentional
  split, not an oversight.
- The three required scenarios (greenfield/brownfield/ambiguous) were interpreted as needing to be
  run against a shared, real, evolving codebase -- so that "brownfield" and "ambiguous" are
  genuinely brownfield and ambiguous with respect to what `service/` actually is at the time each
  one runs, rather than three unrelated toy examples. Concretely: `service/` ships without the
  `GET /api/v1/urls/expired` endpoint and without the hardened `RedirectController`, and the
  brownfield and ambiguous scenarios add those for real, in that order, the first time they run.
- A reviewer's machine has normal internet access to Maven Central for `service/`'s one-time Maven
  dependency download; `orchestrator/`'s own build and tests need no network access at all.

## 5. Limitations acknowledged

See `docs/testing-and-limitations.md` §3 for the full list (WAL compaction, horizontal scaling,
distributed rate limiting, TLS/auth, the orchestrator's JSON-library completeness,
rule-based-vs-LLM agents, simulated failure triggers, file-backed approvals, the two-toolchain
build). None of these affect the correctness of what was built and validated within this
assessment's scope; all are flagged here so a reviewer knows exactly which corners were
consciously cut, and why, rather than discovering them unannounced.
