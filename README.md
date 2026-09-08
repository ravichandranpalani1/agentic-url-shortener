# Agentic Software Engineering System -- URL Shortener

A working URL-shortener prototype (core APIs, analytics, reliability features) built and
governed end-to-end by a custom agentic SDLC orchestration engine, for the Schwab
"Interview Assignment: Build an Agentic Software Engineering System- URL Shortener"
assessment.

This repo contains two independent Java programs:

- **`service/`** -- the URL shortener itself, on **Spring Boot**: create/redirect/analytics/
  delete APIs, rate limiting, an in-process write-ahead log for crash recovery, and a
  health/metrics endpoint.
- **`orchestrator/`** -- the agentic orchestration layer: a dependency-graph engine with
  parallel/sequential execution, human approval gates, bounded retries, fallback,
  rollback, policy guardrails, audit logging, reliability metrics, and dynamic
  re-planning. It runs real agents against the real `service/` codebase. It, along with
  `common/` (JSON) and `testlib/` (its test runner), stays **zero-dependency plain Java** --
  see [`docs/testing-and-limitations.md`](docs/testing-and-limitations.md) for why that
  split exists and the sandboxed-build-environment history behind it.

Everything in this repo is meant to be actually run, not just described: `runs/` fills up
with real audit logs, metrics and generated artifacts the moment you run a scenario (see
Quick start below), and [`docs/testing-and-limitations.md`](docs/testing-and-limitations.md)
covers two real bugs that running this system for real surfaced and fixed.

## Quick start

Requires a JDK (21+) and **Apache Maven** (for `service/`'s Spring Boot build -- `mvn -v` to
check; install from [maven.apache.org](https://maven.apache.org/download.cgi) or your OS
package manager if missing). `orchestrator/`, `common/` and `testlib/` need only the JDK.

```bash
# Build everything (javac for common+testlib+orchestrator, Maven for service/)
scripts/build.sh

# Run the full test suite (orchestrator's hand-rolled runner + service's JUnit 5/Surefire)
scripts/test.sh all

# Start the URL shortener service on :8080
DATA_DIR=./data java -jar service/target/urlshortener-service.jar
# (or, for a dev loop: mvn -f service/pom.xml spring-boot:run)

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

**Run the scenarios in order** (`greenfield`, then `brownfield`, then `ambiguous`) on a
fresh clone: brownfield and ambiguous each apply a real, permanent patch to `service/`
(adding `GET /api/v1/urls/expired`, then hardening the redirect handler's analytics
submission) -- see their scenario docs for exactly what changes and why.

## Repository layout

```
common/          Dependency-free JSON reader/writer, used by orchestrator/ only
testlib/         ~150-line hand-rolled test framework (@Test annotation + reflection runner), for orchestrator/
service/         The URL shortener -- Spring Boot / Maven (see docs/architecture.md)
orchestrator/    The agentic SDLC orchestration engine + its agents + the 3 scenario graphs
scenario-assets/ Pre-drafted "patches" the orchestrator's ImplementationAgent applies for real
approvals/       Pre-recorded human approval decisions consumed by each scenario run
runs/            Real audit logs, metrics, and generated artifacts -- populated by running a scenario
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
