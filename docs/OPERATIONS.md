# Operations

Deployment, configuration, and the operational conventions Bromo follows.

> **Status:** target. The compose file is rebuilt in milestone M1 and the public deployment is part of M4. See the
> roadmap in the [README](../README.md#roadmap).

## Deployment

### Local (Docker Compose)

Prerequisites: Docker Engine 25+ (or OrbStack), JDK 21+, Python 3.12+.

```bash
cp .env.example .env
docker compose -f infra/docker/docker-compose.yml up -d    # ClickHouse
./gradlew :bromo-app:bootRun                               # API and UI on :8080
```

The compose stack applies the numbered SQL files in `infra/clickhouse/` on first start. Check resource use before
adding anything else, disk is often the first limit on a laptop:

```bash
docker stats --no-stream
docker system df
```

Record the output in [benchmarks/README.md](../benchmarks/README.md).

### Single VPS (public demo)

Reference machine: 12 GB RAM, 2 CPU, **ARM (arm64)**. Every image must publish an `arm64` variant. Verify this for
ClickHouse, Caddy, and the base images before choosing versions, and build the Java and Python images for
`linux/arm64`.

| Service | Role | Exposure |
|---|---|---|
| Caddy | TLS, rate limit, reverse proxy | Only public port: 443 |
| bromo-app | API, UI | Through Caddy |
| bromo-agent | gRPC server, detector | Internal network only |
| ClickHouse | Single node | Internal |
| replayer | Loops a slice so the demo keeps moving | Internal |

Steps:

1. Point a DNS name at the VPS.
2. Create an env file outside the repository with the production values below. `BROMO_LLM_DAILY_BUDGET_USD` is
   mandatory.
3. Start the production compose file with that env file.
4. Verify from outside: the UI loads, read endpoints work, chat without a token returns `401`, and ClickHouse,
   gRPC, and management ports are unreachable.

With 2 CPU, cap replay speed and UI polling. CPU, not RAM, is the limit. Memory figures are estimates until measured.

Caddy must disable response buffering for `/api/v1/chat` (`flush_interval -1`) so the event stream is not held back.

### Replication lab (optional)

One shard with two replicas and one ClickHouse Keeper, to demonstrate failover. Check free disk first. The official 2
shard by 2 replica example needs seven containers and is not required for any claim made here.

### Images, upgrades, backups

- `./gradlew :bromo-app:bootBuildImage` builds the Java image. The agent image is built from `bromo-agent/Dockerfile`.
  Containers run as non-root with a read-only root filesystem where possible. GraalVM native images and Helm charts are
  out of scope.
- Pin versions in compose and in `gradle/libs.versions.toml`. Upgrade one component at a time and rerun the
  reconciliation tests and benchmarks.
- Everything is rebuildable from bronze and from the source data. A demo needs no backup. For real use, snapshot the
  ClickHouse volume and test a restore.

## Configuration

Environment variables only (12-factor). [`.env.example`](../.env.example) is the single reference: every variable is
listed there with its default and a comment. Copy it to `.env` and never commit `.env`.

- Required variables have no default. In `prod` the app refuses to start without them.
- Everything else has a default in code (Spring `application.yml`, the Python settings object).
- ClickHouse user names are fixed by the SQL in `infra/clickhouse/` (`ingest_writer`, `api_reader`, `agent_reader`, `findings_writer`).
  Only their passwords are configuration.
- Set only the LLM provider key that matches `LLM_MODEL`.
- A new variable is added to `.env.example` and the compose file in the same change.
- Keep secrets in an env file outside git with mode `0600`, or in Docker secrets. Never pass them on a command line and
  never log them.
- Static keys and tokens are a deliberate simplification. A key table in ClickHouse is the upgrade when there is more
  than one tenant.

Variables read by the current code that are removed in milestone M1: `KAFKA_BOOTSTRAP_SERVERS`, `CASSANDRA_CONTACT_POINTS`, `CASSANDRA_KEYSPACE`,
`CASSANDRA_DATACENTER`, `SCYLLADB_CONTACT_POINTS`, `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `POSTHOG_ENABLED`, `POSTHOG_API_KEY`, `POSTHOG_HOST`.

## Operational conventions

| Area | Convention |
|---|---|
| Configuration | 12-factor: environment variables, no secrets in files under version control |
| Probes | Separate liveness (process only) and readiness (ClickHouse connection). Liveness never checks dependencies. |
| Shutdown | Graceful on `SIGTERM`: stop accepting requests, finish in-flight inserts and gRPC streams within a deadline |
| Logs | Structured JSON to stdout with `traceId`. No secrets, no event payloads at `INFO`. |
| Metrics | Prometheus text format on the management port, Prometheus naming (`snake_case`, base units, `_total`) |
| Traces | OpenTelemetry. W3C `traceparent` accepted at the API and sent to the agent in gRPC metadata. |
| Resource limits | Every container has a memory limit. Request body and event size limits are enforced before parsing. |
| Time | UTC everywhere. RFC 3339 in APIs. |
