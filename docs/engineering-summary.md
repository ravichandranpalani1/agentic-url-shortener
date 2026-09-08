# Final Engineering Summary

## 1. Plan and rationale

The assignment has two distinct deliverables that are easy to conflate: a URL-shortener service,
and an agentic system that *builds and governs* software like that service across a full SDLC.
The plan was to treat the second as the actual assessment target and the first as its test
subject -- a real, non-trivial codebase for the orchestration layer to reason about, modify, and
validate, rather than a toy. Concretely, that meant:

1. Build `service/` first, as an ordinary well-tested Java HTTP application, so it would exist as
   real ground truth (real files, real tests, real bugs) for the orchestrator to operate on.
2. Build `orchestrator/` as a general-purpose engine -- a DAG of `Stage`s, each backed by an
   `Agent`, executed with dependency ordering, parallel batching, approval gates, retry/fallback/
   rollback, guardrails, audit logging, and content-hash re-planning -- with no scenario-specific
   logic baked into the engine itself. Every governance mechanism the assignment asks for lives in
   `Orchestrator`/`WorkflowGraph`/`governance/`/`audit/`, not in any individual agent.
3. Write six small, focused agents (`RequirementsAgent`, `DesignAgent`, `ImplementationAgent`,
   `TestingAgent`, `DocsAgent`, `ReleaseReadinessAgent`, plus fallback/rollback/clarification
   helpers) that do genuine work against the real `service/` tree, rather than agents that produce
   plausible-sounding narration.
4. Compose three scenario graphs (`ScenarioDefinitions`) from those same stages/agents, each
   structured so it naturally headlines a different reliability mechanism required by the
   assignment: greenfield headlines bounded retry, brownfield headlines fallback-after-exhausted-
   retries plus real codebase-impact reasoning, ambiguous headlines ambiguity detection, a human
   clarification gate between two real interpretations, and an isolated rollback.
5. Run all three for real against the real codebase, let two of them (brownfield, ambiguous)
   actually and permanently modify `service/`, and document exactly what happened using the real
   audit logs and metrics each run produced -- not reconstructed or idealized versions of them.

The Maven-unreachable sandbox environment forced an early, consequential decision: pivot to zero
third-party dependencies on pure JDK 21. This is documented as a deliberate trade-off in
`docs/testing-and-limitations.md` rather than treated as a shortcut -- it is also, incidentally, a
realistic constraint for the target audience (a bank's build infrastructure).

## 2. Artifacts produced

- `service/` -- 26 source files: create/redirect/analytics/delete/list APIs, `Base62Encoder`,
  SSRF-guarding `UrlValidator`, prefix-aware `AliasValidator`, token-bucket `RateLimiter`,
  WAL-backed `InMemoryUrlStore` with crash recovery, `HealthHandler`/`MetricsHandler`, and
  `Bootstrap`/`UrlShortenerServer`.
- `orchestrator/` -- the engine (`WorkflowGraph`, `Orchestrator`, `RunStateStore`), 6 governance
  primitives (2 guardrails, 3 approval gate implementations, `PolicyViolationException`), 6 audit/
  metrics classes, 10 agents, and `ScenarioDefinitions` building the 3 required scenario graphs.
- `common/` and `testlib/` -- a dependency-free JSON library and a ~150-line `@Test`-annotation
  reflection-based test runner, both written specifically to keep the whole system buildable with
  no network access.
- 57 tests total (42 for `service`+`common`, 15 for `orchestrator`), all passing as committed.
- Three real, reproducible end-to-end runs under `runs/greenfield/`, `runs/brownfield/`,
  `runs/ambiguous/`: each contains a real `audit.jsonl`, real `metrics`, and real generated
  artifacts (specs, design-impact notes, run reports, a manual-follow-up queue, a release-readiness
  verdict).
- `scenario-assets/` and `approvals/` -- the pre-drafted patches `ImplementationAgent` applies for
  real, and the pre-recorded human approval decisions (with rationale) each scenario's approval
  gates consume.
- Documentation: `README.md`, `docs/architecture.md`, three scenario write-ups
  (`docs/scenarios/01-greenfield.md`, `02-brownfield.md`, `03-ambiguous.md`),
  `docs/testing-and-limitations.md`, and this document.
- `scripts/build.sh`, `scripts/test.sh`, `scripts/run-orchestrator.sh` -- the entire build/run
  surface, no Maven/Gradle wrapper needed.

## 3. Risks, trade-offs, and validation performed

The main trade-offs (zero dependencies, rule-based rather than LLM-backed agents, simulated
failure triggers for reproducibility, file-backed rather than SSO-integrated approvals) are laid
out in full in `docs/testing-and-limitations.md` §4, along with the known limitations (§3: no WAL
compaction, single-instance store, in-process-only rate limiting, no TLS/auth, an
RFC-8259-incomplete JSON library). They are not repeated here in full; the short version is that
every one of them trades generality or production-readiness for reproducibility and
inspectability inside the constraints of this assessment, and each is scoped so that swapping in
the production-grade version (a real datastore, an LLM-backed `RequirementsAgent`, a real
approval/ticketing integration) would not require changing the orchestration engine itself.

Validation performed, concretely:

- Full test suite green as committed: `scripts/test.sh all` → 57/57 passing.
- All three scenario runs completed with a `release` decision of **GO**, with real metrics
  captured in each scenario's write-up (success rates, retry/rollback/approval counts, MTTR where
  applicable, end-to-end latency).
- Re-planning (dynamic re-execution vs. reuse) validated directly by rerunning the greenfield
  scenario twice and observing all 7 stages correctly short-circuit to `REUSED` the second time
  (`docs/scenarios/01-greenfield.md` §5) -- this is also how the numeric-type-fidelity bug (below)
  was actually found.
- Two real, non-hypothetical bugs were found by running the system rather than by inspection, and
  both were root-caused, fixed, and given permanent regression tests:
  1. An HTTP routing prefix-collision in `com.sun.net.httpserver` that could let a custom alias
     shadow a reserved endpoint (or vice versa).
  2. A numeric-type instability (`Integer` vs. JSON-round-tripped `Double`) in the orchestrator's
     content-hash re-planning check, which caused spurious `STALE` re-execution.
  Full root-cause narrative for both: `docs/testing-and-limitations.md` §2.
- The brownfield and ambiguous scenarios' file changes are real, permanent modifications to
  `service/` -- verified by the fact that `GET /api/v1/urls/expired` and the hardened
  `RedirectHandler` exist in the committed source tree, not just described in a run log, and by
  the full suite passing afterward with the new `ExpiredUrlsFeatureTest` included.

## 4. Assumptions

- The assignment's repeated emphasis on a "working runnable prototype" and "audit-grade
  observability/traceability" was read as a requirement that every mechanism (retry, fallback,
  rollback, guardrails, approval gates, re-planning) be demonstrated by actually executing code
  and inspecting its real output, not simulated in prose. This shaped essentially every design
  decision in this repo.
- "Java (Spring Boot)" (the user's stated stack preference) was interpreted as a language/paradigm
  choice; given the network-restricted build environment, the specific dependency (Spring Boot
  itself) was replaced with a hand-wired equivalent on plain JDK 21, documented as an explicit,
  reasoned substitution rather than silently ignored.
- The three required scenarios (greenfield/brownfield/ambiguous) were interpreted as needing to be
  run against a shared, real, evolving codebase -- so that "brownfield" and "ambiguous" are
  genuinely brownfield and ambiguous with respect to the same service `service/` actually is,
  rather than three unrelated toy examples.

## 5. Limitations acknowledged

See `docs/testing-and-limitations.md` §3 for the full list (WAL compaction, horizontal scaling,
distributed rate limiting, TLS/auth, JSON-library completeness, rule-based-vs-LLM agents,
simulated failure triggers, file-backed approvals). None of these affect the correctness of what
was built and validated within this assessment's scope; all are flagged here so a reviewer knows
exactly which corners were consciously cut, and why, rather than discovering them unannounced.
