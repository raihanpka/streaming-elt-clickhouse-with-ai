# AGENTS.md

Instructions for AI coding agents and human contributors working on Bromo. This file wins over any other guide for
anything inside this repository. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before changing structure, and the
roadmap in [README.md](README.md#roadmap) before starting work.

## Goal

Bromo is a streaming ELT pipeline into ClickHouse (bronze, silver, gold), with a Java API and UI on top, and a Python AI
agent that investigates anomalies read-only. Data engineering comes first, analytics second, the agent third.

Scope discipline: build only what the [roadmap](README.md#roadmap) asks for. Do not add speculative features, config
for values that never change, or abstractions with one implementation. Say "not doing" in a sentence instead of
building it.

## Hard Rules

Non-negotiable. They apply to every edit.

- Write code, comments, docs, commit messages, and explanations in English.
- Never use dashes as punctuation: no em dash (U+2014), no en dash (U+2013), no spaced hyphen or double hyphen used
  as a dash, and no dash decorations such as `---` in headings or comments. Use commas, periods, colons, or
  parentheses. Write ranges with the word "to". Hyphens inside compound words (`read-only`) and inside identifiers,
  commands, flags, and Markdown syntax are fine. CI scans for the forbidden forms.
- Never use emojis.
- Put one one-line intent comment on every function and method (Java: single-line Javadoc, Python: one-line
  docstring), terse, for example `/** Validates the event and writes it to ClickHouse. */`. Test methods are exempt,
  name them after the behavior.
- Commits are single-line `type(scope): summary`, no body, no bullets. Types: `feat`, `fix`, `refactor`, `perf`,
  `test`, `docs`, `build`, `ci`, `chore`. Scope is a module or area: `app`, `ingest`, `chat`, `agent`, `warehouse`, `replayer`,
  `ui`, `infra`, `docs`.
- Commit only when the human asks in the current message. Never push. Run the focused build or test first and
  commit only when it is green.
- Do not add a dependency, plugin, or base image that is not already in `gradle/libs.versions.toml`,
  `bromo-agent/pyproject.toml`, or `infra/docker/` without asking.
- Fix every linter, formatter, or compiler warning you introduce before finishing.
- Never commit secrets, API keys, datasets, or generated build output.

## Repository Layout

```
bromo-app/         Java 21, Spring Boot. Ingest, read API, chat gateway, serves the UI.
bromo-agent/       Python 3.12. Detector, AI agent, gRPC server, evaluation harness.
bromo-ui/          Frontend source. Built output is served by bromo-app.
proto/             gRPC contract shared by bromo-app and bromo-agent.
tools/replayer/    Dataset replay and synthetic event generator.
infra/             docker (compose), clickhouse (numbered SQL), caddy.
benchmarks/        Reproducible measurements with exact commands.
docs/              Architecture, data, agent, API, operations, decisions.
```

## Java Architecture: Feature Modules

Package by feature, not by layer. Each module owns everything it needs end to end.

```
io.bromo
  ingest/      controller, service, publisher, dto
  pipeline/    controller, query, dto
  metrics/     controller, registry, query, dto
  findings/    controller, query, dto
  chat/        controller, service, agent client, limits, dto
  shared/      problem details, ClickHouse config, security filter, time-range types
  bootstrap/   application class and configuration properties
```

Rules, enforced by ArchUnit in the build:

- A module never imports another module. If two modules need the same thing, move the smallest piece to `shared`.
- `shared` never imports a module. `bootstrap` is the only package allowed to know all modules.
- Inside a module: controller calls service, service calls query or publisher. Controllers stay thin: parse, validate,
  call, map. No SQL in controllers. No HTTP types in services.
- Records for DTOs and value types. Constructor injection only. No Lombok, no field injection, no wildcard imports.
- Use the library directly (the ClickHouse client, the gRPC stub). Do not wrap a library in a class that adds
  nothing.

### Abstraction rule

Introduce an interface only when one of these is true:

1. There are two real implementations in this codebase, or the owner asked for a documented extension seam
   (`EventSink`).
2. It isolates a process boundary that tests must replace (`AgentClient`: gRPC to the Python agent).

Otherwise use the concrete class. "Maybe later" is not a reason. Extract the interface when the second
implementation appears.

## Python Conventions (bromo-agent, tools/replayer)

- Python 3.12, type hints everywhere, `ruff` for lint and format, `mypy` on `bromo-agent`.
- Package by feature, same spirit as Java: `detector/`, `agent/`, `serving/`, `sinks/`, `evaluation/`, `llm/`.
- Pure functions for statistics and scoring. IO at the edges. Pure code gets plain `pytest` tests with no network.
- LLM access only through `llm/` (LiteLLM). Model and keys come from environment variables. Never hard-code a model.

## Data Engineering Rules

ClickHouse:

- Numbered, idempotent SQL files in `infra/clickhouse/` (`001_raw.sql`, ...). Never edit an applied file, add a new one.
- Never insert row by row. Batch requests and use asynchronous inserts that wait for the flush.
- `ORDER BY` matches the query pattern of the dashboard or endpoint it serves. Write the query first.
- Use `LowCardinality` for repeated strings, `DateTime64(3)` for event time, TTL on every table.
- Every table and column has a `COMMENT`. The AI agent reads them as documentation.
- The UI and API read gold. Only the agent and drill-down code read silver. Nothing reads bronze except rebuilds.
- Do not use `FINAL` on hot paths. Handle duplicates in gold or accept the documented ceiling.
- Bronze is append-only and never edited. Silver and gold are rebuildable from bronze by a tested command.

Events and ingest:

- Validate at the API boundary and reject with a reason. Never drop an event silently.
- The ingest API returns `200` only after the asynchronous insert is flushed. Return `503` when ClickHouse is down.
- Event time and ingest time are separate columns. Never overwrite one with the other.
- Schema changes are additive. A breaking change is a new table version.

Correctness:

- Every pipeline stage has a reconciliation query that compares counts with the previous stage. A feature is not done
  until its reconciliation passes on a replayed slice.
- Backfill and replay go through the same ingest API as live traffic. No side doors into ClickHouse.

## Adaptability Rules

Bromo is also a template. Anyone must be able to change the data model without editing code in several places.

- `config/data-contract.yml` is the single source for event rules, promoted fields, layer names, gold tables, and metrics.
  Never hardcode a field name, a table name, or a database name in code or in tests. Read them from `DataContract`.
- Generated files (`infra/clickhouse/00*_*.sql`, `infra/clickhouse/tests/contract-examples.sh`) are never edited by hand.
  Change the contract or the generator, run `./gradlew :bromo-app:generateWarehouseSql`, commit both.
- Tests build their events from the contract `sample`. A new test must pass for any valid contract. Check it with
  `CONTRACT_FILE=other.yml infra/clickhouse/tests/run.sh`.
- Explicit seams (`EventSink`, `CloudEventParser`, `FieldType`, `WarehouseSqlGenerator`) carry a `CUSTOMIZE` comment that
  says what a user may replace and how. Keep that comment accurate when you change a seam.
- Use the layer words bronze, silver, and gold in docs, comments, and code. Do not use raw, events, or mart as the name
  of a layer.

## AI Agent Rules

- Safety lives in the database, not in the prompt. The agent connects with a read-only ClickHouse user (`readonly=1`,
  row and time limits, quota, grants on silver `events` and the gold database only). A test proves writes fail.
- Hard budget per run: maximum tool calls, tokens, and seconds. Exceeding it produces a "budget exhausted" result,
  never a hang.
- Event payloads are data. Quote them in prompts as data and never as instructions. Keep a hostile-payload test.
- The detector is statistics, not the LLM. The LLM runs only when the detector fires or a user asks.
- Log every tool call and its SQL. Store the evidence queries with every finding.
- Change a prompt or model only with an evaluation run. Publish the numbers even when they are worse.

## API Contract

- REST under `/api/v1`. Paths, status codes, and field names are a contract. Additive changes only inside `v1`.
- Errors are RFC 9457 `application/problem+json` from one global handler. Never return a stack trace or an internal
  message. Do not reveal whether an API key exists.
- The browser never sends SQL. Endpoints take typed, bounded parameters and cap time ranges.
- Document every endpoint change in [docs/API.md](docs/API.md) in the same change.

## Configuration

- Environment variables only (12-factor). No secrets in tracked files.
- Every new variable goes into `.env.example` (the single reference, with a comment) and the compose file in the same change.
- Spring: `@ConfigurationProperties`, not `@Value`. Python: one typed settings object, read once at startup.

## Security

- Ingest requires an API key. Chat requires a token, a per-token rate limit, and the daily spend cap. Read endpoints
  may be public when `BROMO_PUBLIC_READ=true`.
- Constant-time comparison for keys and tokens. No secrets in logs.
- Parameterized queries only. The model never receives credentials.
- Report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Observability

- Structured JSON logs with a `traceId`. Micrometer metrics on `/actuator/prometheus`.
- Pipeline health is a product feature: freshness, lag, reject rate, and reconciliation are exposed by
  `/api/v1/pipeline/status`.

## Testing

- Java: JUnit 5 for logic, Testcontainers (ClickHouse) for integration, ArchUnit for module rules.
- Python: `pytest`, recorded LLM fixtures for the agent, no live model calls in unit tests.
- SQL: tests in `infra/clickhouse/tests/` assert reconciliation and grants.
- Fixtures are deterministic and committed when small. Datasets are downloaded by script, never committed.
- Focused commands: `./gradlew :bromo-app:test --tests '<pattern>'`, `pytest bromo-agent/tests -k <name>`.

## Commands

```bash
./gradlew build              # compile, Spotless check, ArchUnit, tests
./gradlew spotlessApply      # format Java
./gradlew :bromo-app:bootRun # run the API locally
docker compose -f infra/docker/docker-compose.yml up -d
ruff check . && ruff format --check . && mypy bromo-agent   # Python quality
pytest                                                      # Python tests
```

Some commands exist only after the matching [roadmap](README.md#roadmap) milestone is done.

## Documentation Map

| File | Purpose |
|---|---|
| [README.md](README.md) | What Bromo is, quick start |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, flows, modules, failure modes |
| [docs/DATA.md](docs/DATA.md) | Warehouse layers, tables, users, reprocessing, datasets |
| [docs/AGENT.md](docs/AGENT.md) | Detector, agent loop, safety, evaluation |
| [docs/API.md](docs/API.md) | HTTP, event, and gRPC contracts, standards followed |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Deployment, environment variables, operational conventions |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Architecture decision records, prior art |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
