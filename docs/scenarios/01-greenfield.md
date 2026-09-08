# Scenario 1 -- Greenfield: build the service from scratch

**Run it yourself:** `scripts/run-orchestrator.sh greenfield` (uses `approvals/greenfield.json`
for reproducibility; add `--auto` to skip straight to auto-approval instead).

## Requirement (as given to `RequirementsAgent`)

> Build a URL shortener service from scratch with a create API, a redirect API, per-code
> click analytics, custom aliases, link expiration, rate limiting, and a health/metrics
> endpoint. It must handle at least 3 concurrent redirects without errors and must recover
> its state after a restart.

This is deliberately well-defined (concrete feature list, a numeric concurrency target, a
hard "must" constraint on restart recovery) -- see `docs/scenarios/03-ambiguous.md` for the
counterpart with an underspecified requirement.

## 1. Requirement understanding

`RequirementsAgent` matched the text against its domain vocabulary and found 8 areas:
`analytics, rate limit, redirect, alias, expiration, health, metrics, click`, and flagged it
**not ambiguous** (a numeric target plus a hard "must" constraint are both present). Real
output: `runs/greenfield/artifacts/requirements-spec.md`.

## 2. Task decomposition (the graph)

```
requirements ─▶ design ─▶ implementation ─▶ testing ─┬▶ docs   ─┐
                                                       └▶ notify ─┴▶ release
```

7 stages, 6 batches (`docs` and `notify` are the one parallel pair -- both depend only on
`testing`, neither on the other, so the engine runs them concurrently with a
synchronization barrier before `release`). This is a small graph by design: greenfield work
here mostly *is* the codebase itself (already built), so the orchestration exercise is
about verifying and gating that build, not regenerating it stage-by-stage from nothing --
see `docs/testing-and-limitations.md` for why "have an LLM write 2,000 lines live inside
one stage" wasn't the design chosen.

## 3. Orchestration walkthrough (real run, `runs/greenfield/audit.jsonl`)

- **`design`**: scanned `service/src/main/java` for real and reported every file where the
  8 matched keywords appear (`runs/greenfield/artifacts/design-impact-note.md`, generated
  fresh each time you run this) -- correctly identifying that the already-built service *is*
  the surface this requirement touches.
- **`implementation`** (mode `verify-scaffold`, `maxRetries=3`): configured with
  `simulateTransientFailureUntilAttempt=2` to deterministically exercise the bounded-retry
  path against a documented failure class (a flaky dependency-resolution step) rather than
  waiting for a real one to show up on demand. Real log:
  ```
  [ATTEMPT_FAILED] implementation -- Simulated transient failure ... on attempt 1 of 2
  [RETRY] implementation (attempt 2)
  [COMPLETED] implementation (attempt 3)
  ```
  It also requires human approval ("marking the full service surface area as built
  requires sign-off") -- see `approvals/greenfield.json` for the recorded approver and
  rationale.
- **`testing`**: shells out to the real test suite (`scripts/test.sh service`) and parses
  its actual JSON summary; this run: all service tests passed.
- **`docs` / `notify`**: run in parallel (see `[BATCH_STARTED]` for both in the same
  batch in the audit log); `notify` succeeds on its first attempt in this scenario (its
  failure/fallback path is exercised instead in the brownfield scenario).
- **`release`**: `ReleaseReadinessAgent` checked the testing stage's real pass/fail data
  and the decision lineage for guardrail violations or rejected approvals, found none,
  decision **GO**.

## 4. Metrics from this run

Every run of `scripts/run-orchestrator.sh greenfield` prints a real metrics block
(`totalStages`, `completedStages`, `successRate`, `totalRetries`, `totalRollbacks`,
`approvalsRequested`, `mttrMillis`, `endToEndLatencyMillis`, ...) and writes the same
numbers to `runs/greenfield/state.json` and the audit trail to `runs/greenfield/audit.jsonl`
-- inspect those files after running it locally for this run's actual numbers. On a fresh
run you should see `totalStages=7`, `completedStages=7`, `successRate=1.0`,
`totalRetries=1` (the `implementation` stage's simulated transient failure recovering on
attempt 3), `totalRollbacks=0`, and `approvalsRequested=1` (the `implementation` stage's
gate).

## 5. Re-planning, demonstrated on this exact scenario

Running `scripts/run-orchestrator.sh greenfield --auto` a second time immediately
afterward, with nothing changed, reuses every stage instead of re-executing it:

```
FINAL STAGE STATUSES: design=REUSED docs=REUSED implementation=REUSED notify=REUSED
                       release=REUSED requirements=REUSED testing=REUSED
totalRetries=0  reusedStages=7  successRate=1.0
```
End-to-end latency drops sharply (content-hash re-planning short-circuits every stage to a
cache hit) -- compare `endToEndLatencyMillis` between the first and second run's
`runs/greenfield/state.json` to see it directly. This is also exactly the kind of run that
surfaced the numeric-type-fidelity bug described in `docs/testing-and-limitations.md` --
before that fix, the second run's statuses could look wrong (`testing` and `release`
re-executing for no visible reason); they report correctly now.

## 6. Validation

- Every file listed in this scenario's `expectedFiles` (see `ScenarioDefinitions`) verified
  present after the run.
- Full real test suite green: run `scripts/test.sh service` (or `scripts/test.sh all`) and
  confirm all green; the orchestrator's own `testing` stage shells out to the same command
  and records its JSON summary at
  `runs/greenfield/artifacts/test-summary-service.json`.
- No guardrail violations, no rejected approvals, release decision GO.
- Re-run reproducibility validated directly (section 5).
