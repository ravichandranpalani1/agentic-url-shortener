# Agentic URL Shortener

A working URL-shortener prototype (core APIs, analytics, reliability features) built and
governed end-to-end by a custom agentic SDLC orchestration engine, for the Schwab
"Build an Agentic Software Engineering System" assessment.

This repo contains two independent Java programs, both built with **zero third-party
dependencies** (see [`docs/testing-and-limitations.md`](docs/testing-and-limitations.md)
for why):

- **`service/`** -- the URL shortener itself: create/redirect/analytics/delete APIs, rate
  limiting, an in-process cache-free write-ahead log for crash recovery, and a
  health/metrics endpoint.
- **`orchestrator/`** -- the agentic orchestration layer: a dependency-graph engine with
  parallel/sequential execution, human approval gates, bounded retries, fallback,
  rollback, policy guardrails, audit logging, reliability metrics, and dynamic
  re-planning. It runs real agents against the real `service/` codebase.

Everything in this repo was actually run, not just described: see `runs/greenfield/`,
`runs/brownfield/` and `runs/ambiguous/` for the real audit logs, metrics and generated
artifacts from the three required scenario runs, and
[`docs/testing-and-limitations.md`](docs/testing-and-limitations.md) for two real bugs
that running this system for real surfaced and fixed.

## Quick start

Requires only a JDK (21+) -- no Maven/Gradle, no internet access, no other tooling.

```bash
# Build everything (common + testlib + service + orchestrator)
scripts/build.sh

# Run the full test suite (57 tests across all modules)
scripts/test.sh all

# Start the URL shortener service on :8080
DATA_DIR=./data java -cp out/classes com.schwab.urlshortener.UrlShortenerServer

# In another shell:
curl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://www.schwab.com/pricing"}'
# => {"code":"1","shortUrl":"http://localhost:8080/1", ...}
curl -sI localhost:8080/1   # 302 redirect to the long URL

# Run an orchestrator scenario against the real codebase (writes real files, runs real tests)
scripts/run-orchestrator.sh greenfield   # or: brownfield | ambiguous
```

`scripts/run-orchestrator.sh <scenario>` picks up that scenario's pre-recorded human
approvals from `approvals/<scenario>.json` automatically. Pass `--auto` instead to
auto-approve everything (clearly flagged as simulated in the audit log; for smoke-testing
only), or omit both to be prompted interactively on the console.

## Repository layout

```
common/          Dependency-free JSON reader/writer shared by both programs
testlib/         ~150-line hand-rolled test framework (@Test annotation + reflection runner)
service/         The URL shortener (see docs/architecture.md for the component breakdown)
orchestrator/    The agentic SDLC orchestration engine + its 6 agents + the 3 scenario graphs
scenario-assets/ Pre-drafted "patches" the orchestrator's ImplementationAgent applies for real
approvals/       Pre-recorded human approval decisions consumed by each scenario run
runs/            Real audit logs, metrics, and generated artifacts from actual scenario runs
docs/            Architecture, the 3 scenario write-ups, testing/limitations, engineering summary
scripts/         build.sh, test.sh, run-orchestrator.sh -- the whole build/run surface
```

## Documentation

- [`docs/architecture.md`](docs/architecture.md) -- components, orchestration model, control
  flow, key design decisions
- [`docs/scenarios/01-greenfield.md`](docs/scenarios/01-greenfield.md) -- building the
  service from scratch under orchestration (headlines: retry recovering a transient failure)
- [`docs/scenarios/02-brownfield.md`](docs/scenarios/02-brownfield.md) -- adding
  `GET /api/v1/urls/expired` to the existing service (headlines: fallback after exhausted
  retries, real codebase-impact scanning)
- [`docs/scenarios/03-ambiguous.md`](docs/scenarios/03-ambiguous.md) -- "make the analytics
  more reliable" (headlines: ambiguity detection, human clarification gate, real rollback)
- [`docs/testing-and-limitations.md`](docs/testing-and-limitations.md) -- testing approach,
  two real bugs found by actually running this system, known limitations and trade-offs
- [`docs/engineering-summary.md`](docs/engineering-summary.md) -- the final engineering
  summary: plan/rationale, artifacts, risks, validation, assumptions, limitations
