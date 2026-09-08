# Scenario 3 -- Ambiguous: "make the analytics more reliable"

**Run it yourself:** `scripts/run-orchestrator.sh ambiguous` (uses `approvals/ambiguous.json`).

## Requirement

> Make the click analytics more reliable.

No acceptance criteria, no numeric target, and "reliable" is exactly the kind of
qualitative term `RequirementsAgent`'s heuristic exists to catch.

## 1. Requirement understanding: detecting the ambiguity

`RequirementsAgent` matched "reliable" against its vague-term list, found no numeric target
and no hard ("must"/"shall") constraint, and flagged **ambiguous: true** with the note:
*"Requirement uses qualitative terms without measurable acceptance criteria: reliable"*
(`runs/ambiguous/artifacts/requirements-spec.md`). This is not a special case in the
graph -- every scenario's requirement stage runs the same heuristic; this is simply the one
where it fires.

## 2. Human clarification gate

Because the requirement is ambiguous, this scenario's graph inserts a dedicated
`clarify-requirement` stage between `requirements` and `design`, gated on human approval,
with two concrete interpretations put in front of the approver
(`approvals/ambiguous.json`):

- **Rejected**: record analytics synchronously before responding -- guarantees no click is
  ever lost, at the cost of coupling redirect latency to the analytics write path. A real,
  compilable interpretation (`scenario-assets/ambiguous/RedirectHandler.synchronous-rejected.java`),
  not a strawman -- and exactly the kind of trade-off this gate exists to catch before it
  ships, because it silently reverses `RedirectHandler`'s documented design decision that
  redirect latency must never depend on the analytics write (`docs/architecture.md`, §2.3).
- **Approved**: defend the analytics executor submission against
  `RejectedExecutionException` so a saturated/shutting-down executor degrades to a logged
  drop instead of throwing on the request thread, while keeping analytics fully
  asynchronous (`scenario-assets/ambiguous/RedirectHandler.retry-approved.java`) -- the one
  actually applied to `service/src/main/java/.../RedirectHandler.java` by the
  `implementation` stage.

The recorded rationale (`approvals/ambiguous.json`) is explicit about *why*: the approved
interpretation preserves the async latency guarantee; the rejected one reverses it for a
benefit nobody asked for. That reasoning -- and the fact that a human, not an agent, made
the call -- is what the assessment's "controlled autonomy" requirement is asking for, and
it's captured in the workflow's decision lineage (`runs/ambiguous/artifacts/run-report.md`)
alongside every other stage's summary.

## 3. A real rollback, isolated from the real change

Separately from applying the approved interpretation, this run also exercises the
orchestrator's rollback mechanism against a harmless scratch file
(`docs/scenarios/_rollback_drill_scratch.md`, deliberately *not* a compiled source file),
so the mechanism is proven inside a real run without any risk to the actual change:

```
[STARTED] rollback-drill
[ATTEMPT_FAILED] rollback-drill -- Post-write self-check failed for [...] (simulated) --
                                    this stage's rollback agent will now revert every file
                                    it just wrote
[ROLLED_BACK] rollback-drill
[FAILED] rollback-drill
```

`ImplementationAgent` wrote the file for real; because it had no pre-existing content,
`RevertFileAgent` deleted it rather than restoring a backup (the same agent restores from a
backup when a target *did* pre-exist -- see `docs/architecture.md`, §3.7). Verified
directly: `docs/scenarios/_rollback_drill_scratch.md` does not exist in this repo. Nothing
in the graph depends on `rollback-drill`, so its (expected) failure doesn't block
`release` -- `rollback-drill` runs in the same batch as the real `implementation` stage
(both depend only on `design`), demonstrating the parallel/sequential mix again: two
independent stages, one that must succeed for the release to proceed and one that is
allowed to fail by design.

## 4. Real metrics from this run

```
totalStages=9  completedStages=8  failedStages=1  skippedStages=0
successRate=0.8889  totalRetries=0  totalRollbacks=1
approvalsRequested=2  approvalsRejected=0  endToEndLatencyMillis=6812
```
(2 approvals: `clarify-requirement` and `implementation`. 1 rollback: `rollback-drill`.)

## 5. Validation

- Full test suite after this scenario's change: 57/57 passing, including the pre-existing
  `UrlShortenerIntegrationTest#fullLifecycle_createRedirectAnalyticsDelete`, which exercises
  the modified `RedirectHandler` end-to-end (create, redirect, click recorded, analytics
  read back) and provides regression coverage for the change without a dedicated new test
  file.
- `release` decision: **GO**.
- The rejected interpretation's file
  (`scenario-assets/ambiguous/RedirectHandler.synchronous-rejected.java`) is kept in the
  repo as a scenario asset only -- it was never applied to `service/`, and its javadoc
  explains exactly why it was rejected, so the decision is auditable without needing the
  audit log.
