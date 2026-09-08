# Scenario 2 -- Brownfield: list expired-but-uncleaned short URLs

**Run it yourself:** `scripts/run-orchestrator.sh brownfield` (uses `approvals/brownfield.json`).

This run genuinely modifies the live `service/` source tree -- it's how the
`GET /api/v1/urls/expired` endpoint that exists in this repo today was actually added.

## Requirement

> Add a way to list short URLs that are past their expiration but have not been cleaned up
> yet, so an operator can find them without scanning every record by hand.

Well-defined, but purely brownfield -- it only makes sense in terms of the existing
`UrlStore`/`UrlRecord` model, so this scenario's design stage has real work to do.

## 1. Codebase reasoning (real, not narrated)

`RequirementsAgent` matched `expire`/`expiration` in its domain vocabulary.
`DesignAgent` then walked the actual `service/src/main/java` tree and reported every file
whose content or filename matched -- correctly surfacing `UrlRecord.java` (owns
`isExpired`), `UrlItemHandler.java` (owns the `/api/v1/urls/{code}` resource family this
belongs under) and `InMemoryUrlStore.java`/`UrlStore.java` (own `recent()`, the method the
implementation ends up reusing) among the impacted files
(`runs/brownfield/artifacts/design-impact-note.md`). This is what let the implementation
land as a **read-only extension of an existing handler using an already-public method**
(`UrlStore.recent()`) rather than a new store-layer API.

## 2. The change actually applied

Three files, copied from pre-drafted patches in `scenario-assets/brownfield/` onto their
real targets by `ImplementationAgent`:

- `service/src/main/java/com/schwab/urlshortener/http/UrlItemHandler.java` -- adds a
  `GET /api/v1/urls/expired` branch, handled *inside* the existing handler rather than as a
  new `HttpServer` context, specifically to avoid recreating the routing-collision bug
  described below and in `docs/testing-and-limitations.md`.
- `service/src/main/java/com/schwab/urlshortener/validation/AliasValidator.java` -- adds
  `expired` to the reserved-alias list, for the same reason.
- `service/src/test/java/com/schwab/urlshortener/ExpiredUrlsFeatureTest.java` -- a new,
  permanent test covering both the endpoint's behavior (only expired+active records listed,
  a permanent record never appears) and the alias-reservation.

Every one of these was a real file write; `ImplementationAgent` also snapshotted each
target's pre-existing content to `runs/brownfield/backups/` before overwriting, the same
mechanism the ambiguous scenario's rollback demo relies on.

### Why this bug class mattered enough to design around twice

`com.sun.net.httpserver.HttpServer` matches contexts by string prefix. Registering
`/api/v1/urls/expired` as its own context would have silently shadowed any real short code
literally named `expired`, reachable via `GET /api/v1/urls/{code}`. This exact bug class
was already found and fixed once in this repo (`docs/testing-and-limitations.md`, bug #1);
this scenario's design deliberately routes around it a second time rather than
reintroducing it, and `AliasValidator`'s reserved-word list is extended rather than
duplicated.

## 3. Orchestration walkthrough -- the fallback path, for real

The `notify` stage is configured with `alwaysFail=true` for this scenario, simulating a
downstream release-notification endpoint that is completely unavailable -- a fixture that
guarantees its retries are genuinely exhausted rather than hoping a real flaky dependency
cooperates on demand:

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
`implementation` also gated on human approval here (`approvals/brownfield.json`), since it
touches existing, tested routing logic.

## 4. Real metrics from this run

```
totalStages=7  completedStages=7  successRate=1.0
totalRetries=1  totalRollbacks=0  approvalsRequested=1  approvalsRejected=0
mttrMillis=103.0  endToEndLatencyMillis=7547
```

## 5. Validation

- New test `ExpiredUrlsFeatureTest` passes as part of the real `testing` stage run.
- Full suite after this scenario's changes: 57/57 passing
  (`scripts/test.sh all`, up from 55 before the brownfield feature existed).
- `release` decision: **GO**.
- Manually verified end-to-end against a real running instance (`DATA_DIR=/tmp/verify-data
  PORT=8099 java -cp out/classes com.schwab.urlshortener.UrlShortenerServer`):
  `curl localhost:8099/api/v1/urls/expired` returns `{"count":0,"items":[]}` on an empty store;
  creating a URL with `ttlSeconds:1` and waiting 2s, the same call returns
  `{"count":1,"items":[{"code":"1","longUrl":"...","expiresAt":...}]}`; and
  `POST /api/v1/urls {"customAlias":"expired-drop"}` is rejected with HTTP `400` and body
  `{"error":"INVALID_REQUEST","message":"customAlias 'expired-drop' would be shadowed by the
  reserved '/expired' path"}`.
