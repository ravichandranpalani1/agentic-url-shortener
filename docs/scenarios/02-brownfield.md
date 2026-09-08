# Scenario 2 -- Brownfield: list expired-but-uncleaned short URLs

**Run it yourself:** `scripts/run-orchestrator.sh brownfield` (uses `approvals/brownfield.json`).

This run genuinely modifies the live `service/` source tree -- running it is how the
`GET /api/v1/urls/expired` endpoint gets added to this repo's `service/` for the first time.
`service/` ships without it; this scenario is what adds it, for real, the first time it runs.

## Requirement

> Add a way to list short URLs that are past their expiration but have not been cleaned up
> yet, so an operator can find them without scanning every record by hand.

Well-defined, but purely brownfield -- it only makes sense in terms of the existing
`UrlStore`/`UrlRecord` model, so this scenario's design stage has real work to do.

## 1. Codebase reasoning (real, not narrated)

`RequirementsAgent` matched `expire`/`expiration` in its domain vocabulary.
`DesignAgent` then walked the actual `service/src/main/java` tree and reported every file
whose content or filename matched -- correctly surfacing `UrlRecord.java` (owns
`isExpired`), `UrlItemController.java` (owns the `/api/v1/urls/{code}` resource family this
belongs under) and `InMemoryUrlStore.java`/`UrlStore.java` (own `recent()`, the method the
implementation ends up reusing) among the impacted files
(`runs/brownfield/artifacts/design-impact-note.md`, generated fresh each time you run this).
This is what let the implementation land as a **read-only extension of an existing controller
using an already-public method** (`UrlStore.recent()`) rather than a new store-layer API.

## 2. The change actually applied

Three files, copied from pre-drafted patches in `scenario-assets/brownfield/` onto their
real targets by `ImplementationAgent`:

- `service/src/main/java/com/schwab/urlshortener/web/UrlItemController.java` -- adds a
  `@GetMapping("/api/v1/urls/expired")` method alongside the existing `{code}` mappings in
  the same controller.
- `service/src/main/java/com/schwab/urlshortener/validation/AliasValidator.java` -- adds
  `expired` to the reserved-word list, so a real short code can never be created that would
  be permanently shadowed by this more specific mapping.
- `service/src/test/java/com/schwab/urlshortener/ExpiredUrlsFeatureTest.java` -- a new,
  permanent test covering both the endpoint's behavior (only expired+active records listed,
  a permanent record never appears) and the alias-reservation.

Every one of these is a real file write; `ImplementationAgent` also snapshots each target's
pre-existing content to `runs/brownfield/backups/` before overwriting, the same mechanism
the ambiguous scenario's rollback demo relies on.

### Why the reserved-word list still matters under Spring's routing

An earlier, zero-dependency version of this service used
`com.sun.net.httpserver.HttpServer`, which matches contexts by raw string prefix -- so
registering `/api/v1/urls/expired` as its own context would have silently shadowed *any*
real short code merely starting with `expired`. That bug class doesn't exist under Spring
MVC's exact-path-segment routing (see `docs/architecture.md` §2.6 and
`docs/testing-and-limitations.md` bug #1). A narrower version of the same risk remains,
though: `/api/v1/urls/expired` and `/api/v1/urls/{code}` are still two mappings on the same
path shape, and Spring always prefers the exact literal match, so a short code *exactly*
equal to `expired` would be permanently unreachable via `GET`. `AliasValidator` reserving
`expired` (exactly, not as a prefix) is what prevents that -- see
`AliasValidator`'s javadoc and `UrlItemController`'s javadoc for the full reasoning.

## 3. Orchestration walkthrough -- the fallback path, for real

The `notify` stage is configured with `alwaysFail=true` for this scenario, simulating a
downstream release-notification endpoint that is completely unavailable -- a fixture that
guarantees its retries are genuinely exhausted rather than hoping a real flaky dependency
cooperates on demand. Running the scenario produces an audit trail like:

```
[STARTED] notify
[ATTEMPT_FAILED] notify -- Simulated: downstream release-notification endpoint is unavailable
[RETRY] notify (attempt 2)
[ATTEMPT_FAILED] notify (attempt 2) -- Simulated: downstream release-notification endpoint is unavailable
[FALLBACK_INVOKED] notify (attempt 2)
[COMPLETED] notify (attempt 2)
```

`QueueForManualFollowUpAgent` (the fallback) writes a real artifact --
`runs/brownfield/artifacts/manual-follow-up-queue.md` -- so a human/on-call process has
something concrete to act on, and the stage still reports `COMPLETED` (via the fallback),
so `release` is not blocked by an outage in a non-critical downstream integration.
`implementation` also gates on human approval here (`approvals/brownfield.json`), since it
touches existing, tested routing logic.

## 4. Metrics from this run

Every run of `scripts/run-orchestrator.sh brownfield` prints a real metrics block
(`totalStages`, `completedStages`, `successRate`, `totalRetries`, `approvalsRequested`,
`mttrMillis`, `endToEndLatencyMillis`, ...) and writes the same numbers to
`runs/brownfield/state.json` and the audit trail to `runs/brownfield/audit.jsonl` -- inspect
those files after running it locally for this run's actual numbers. On a fresh run you
should see `totalStages=7`, `completedStages=7`, `successRate=1.0`, `totalRetries=1` (the
`notify` stage's two exhausted attempts before its fallback completes it), and
`approvalsRequested=1` (the `implementation` stage's gate).

## 5. Validation

- New test `ExpiredUrlsFeatureTest` runs as part of this scenario's real `testing` stage.
- Full suite after this scenario's changes: run `scripts/test.sh all` and confirm all green.
- `release` decision: **GO** (printed at the end of the run; a `NO-GO` would mean a test
  failed or a guardrail tripped, and the run's exit code would be non-zero -- see
  `scripts/run-orchestrator.sh`).
- To verify the endpoint by hand afterward: start the service
  (`DATA_DIR=./data java -jar service/target/urlshortener-service.jar`), then
  `curl localhost:8080/api/v1/urls/expired` should return `{"count":0,"items":[]}` on an
  empty store, and `POST /api/v1/urls` with `{"customAlias":"expired"}` should be rejected
  with `400 INVALID_REQUEST`.
