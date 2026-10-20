# Data

The ClickHouse warehouse model, how data is delivered into it, and the public datasets used to demonstrate it. SQL
lives in `infra/clickhouse/` as numbered files. Column lists here are the design, and the final DDL is tested in
milestone M1. Any difference is recorded in [DECISIONS.md](DECISIONS.md).

## Layers

| Layer | Name | Contents | Written by | Read by |
|---|---|---|---|---|
| Bronze | `raw` | Exactly what the ingest API stored, one row per event | `bromo-app` ingest (`ingest_writer`) | Reprocessing and the silver views |
| Silver | `events` | Parsed, typed, validated, deduplicated events | Materialized view `events_mv` from `raw` | Agent drill-down, mart builders |
| Silver | `events_rejected` | Rows that failed parsing or validation, with a reason | Materialized view `events_rejected_mv` from `raw` | Pipeline status, debugging |
| Gold | `mart_*` | Per-minute aggregates | Materialized views from `events` | API, detector, UI |
| Output | `agent_findings` | Agent results | `bromo-agent` (findings writer) | `bromo-app` |

The API and UI read gold. Only the agent and drill-down code read silver. Only reprocessing reads bronze.

## Conventions

- Names are `snake_case`. Marts are `mart_{subject}_{grain}` (`mart_events_1m`). Materialized views end in `_mv`.
- All timestamps are UTC: `DateTime64(3, 'UTC')`.
- Every table has a `TTL`, a `COMMENT`, and a documented `ORDER BY` that matches the queries it serves.
- Low-cardinality text uses `LowCardinality(String)`. The partition key is monthly, never finer.
- SQL files are numbered and idempotent. An applied file is never edited, add a new one.
- Changes are additive. A breaking change creates a new table or topic version (`events_v2`, `raw.events.v2`).
- Every column carries a `COMMENT`. The `mcp-clickhouse` tools expose comments to the agent, and ClickHouse names table
  comments as the main defense against wrong SQL. Comments are part of the contract.

## Tables

Retention values are starting points, adjusted after measurement.

### `raw` (bronze)

| Column | Type |
|---|---|
| `request_id` | UUID, one per ingest request, also in logs |
| `payload` | String, the event JSON unmodified |
| `ingested_at` | DateTime64(3, 'UTC'), default `now64(3)` |

MergeTree, order by `(ingested_at, request_id)`, partition by `toYYYYMM(ingested_at)`, TTL 30 days. The ingest API
writes with asynchronous inserts (`async_insert=1`, `wait_for_async_insert=1`) so a response means the data is
flushed. `async_insert_deduplicate=1` makes a retried identical batch a no-op.

### `events` (silver)

| Column | Type | Note |
|---|---|---|
| `event_id` | String | CloudEvents `id` |
| `source` | LowCardinality(String) | |
| `type` | LowCardinality(String) | |
| `time` | DateTime64(3, 'UTC') | Event time from the producer |
| `ingested_at` | DateTime64(3, 'UTC') | Carried over from `raw` |
| `subject` | String | Optional |
| `amount` | Nullable(Float64) | `Nullable` is deliberate: an event without an amount must not count as zero in averages |
| `label` | LowCardinality(String) | For example `fraud` or `ok`, empty when absent |
| `attrs` | Map(String, String) | Remaining payload. The native `JSON` type is used only if stable in the pinned version. |

ReplacingMergeTree, order by `(type, source, toStartOfMinute(time), event_id)`, partition by `toYYYYMM(time)`, TTL 90
days. Rows with the same key collapse on merge. Rows whose event time is already older than the TTL are dropped at insert,
which is why the API rejects events older than the retention. Typed hot columns keep common filters fast while events stay
schema-agnostic. Promoting an attribute to a column is an additive change.

### `events_rejected` (silver)

`request_id`, `reason` (`invalid_json`, `missing_id`, `bad_time`, and so on), `payload`, `ingested_at`. TTL 30 days.
Two materialized views read `raw` with complementary predicates: rows that pass validation go to `events`, the rest go
to `events_rejected` with a computed `reason`. The API already rejects invalid events with `400`, so this is a second
line of defense.

### `mart_events_1m` (gold)

Per `(type, source, minute)`: count, sum of `amount`, quantile states of `amount`, unique-subject state, count by
`label`. AggregatingMergeTree fed by a materialized view from `events`, order by `(type, source, minute)`, TTL 365
days, read with the `-Merge` combinators. Add another mart only when an endpoint needs a different key. Cloudflare's
per-breakdown minutely views are the pattern: one view per query shape.

### `agent_findings` (output)

`finding_id` (UUIDv7 stored as UUID), `created_at`, `kind`, `metric`, `window_start`, `window_end`,
`dimension_name`, `dimension_value`, `severity`, `confidence`, `summary`, `evidence_sql` (Array(String)), `status`,
`model`, `tokens`, `cost_usd`. Enum-like columns are `LowCardinality(String)` holding the same lowercase values the
API returns.

## Users and grants

| User | Used by | Rights |
|---|---|---|
| `ingest_writer` | `bromo-app` ingest | `INSERT` on `raw` only |
| `api_reader` | `bromo-app` read API | `SELECT` on `mart_*` and `agent_findings`, row and time limits |
| `agent_reader` | `bromo-agent` | `readonly=1`, `SELECT` on `events` and `mart_*`, `max_execution_time`, `max_rows_to_read`, `max_result_rows`, quota |
| `findings_writer` | `bromo-agent` | `INSERT` on `agent_findings` only |

`agent_reader` has no grant on `raw`, `events_rejected`, or system tables. A test proves that `INSERT`, `DROP`, and
`ALTER` fail for it. The materialized views are created with `DEFINER = admin SQL SECURITY DEFINER`, so `ingest_writer`
needs only `INSERT` on `raw`. Both behaviors are checked on ClickHouse 26.3 by `infra/clickhouse/tests/t_schema.sh`.

## Delivery guarantees

| Concern | Behavior |
|---|---|
| Delivery | The API acknowledges after the flush. Clients retry on `503`, so delivery is at least once. |
| Ordering | Not guaranteed. Queries use event time. |
| Duplicates | `events` collapses duplicates by key on merge. Marts can over-count by the duplicate rate until a rebuild. The rate is measured and published. A retried identical batch is deduplicated by `async_insert_deduplicate`. |
| Bad data | Goes to `events_rejected` with a reason. Nothing is dropped silently. |
| Backpressure | ClickHouse unavailable: ingest returns `503` and there is no durable buffer. A client that stops retrying loses its events. |
| Replay | Source data is replayed through the ingest API. Silver and gold are rebuilt from `raw` with `infra/clickhouse/reprocess.sql`. |

## Reconciliation

Each stage is comparable with the previous one. These checks live in `infra/clickhouse/tests/` and appear in
`GET /api/v1/pipeline/status`:

| Check | Meaning |
|---|---|
| Rows in `raw` versus rows in `events` plus `events_rejected` | Every row is modeled or rejected |
| Sum in `mart_events_1m` versus `events` for the same window | Marts match silver |
| Replayer events sent versus rows in `events` after merge | End to end |

`reprocess.sql` truncates silver and gold and rebuilds them from `raw`. It is tested: totals before and after must
match. Backfill never bypasses the ingest API.

## Datasets

Real data from running banks, insurers, or streaming services is private. Public options are research datasets. Bromo
uses them through **replay**: the replayer reads a timestamped slice and emits it as events. The pipeline cannot tell
the difference from live traffic, and labels give ground truth for evaluation.

> **License notice.** Licenses were not verified when this was written. Check each dataset's page before publishing
> anything derived from it. Datasets are never committed. Scripts download them.

| Dataset | What it is | Size | Labels | Role |
|---|---|---|---|---|
| ULB credit card fraud (Kaggle) | Real European card transactions, PCA-anonymized features, `Time` offset, `Class` | 284,807 rows, 492 fraud | Yes | Primary real-data story |
| IEEE-CIS fraud (Vesta, Kaggle) | Real e-commerce transactions with card, product, device columns | 590,540 rows, about 3.5% fraud | Yes | Optional, richer dimensions |
| Synthetic LLM telemetry (own generator) | Completions, tokens, latency, cost, feedback | Any | Injected anomalies with a manifest | Controlled agent evaluation |
| MovieLens (GroupLens) | Real ratings with timestamps | Varies | No anomaly labels | Optional adapter. Redistribution is restricted, download only. |
| ClickHouse GitHub events | Public GitHub activity 2011 to 2020 | About 3.1 billion rows | No | Optional schema volume test |
| Wikimedia recent changes | Public live edit stream | Live | No | Optional live source. Check terms first. |

Not used: PaySim (synthetic, and balance columns leak the label). Insurance: no good public transactional dataset was
found, so none is claimed.

### Slicing and time

Large datasets are taken in chunks: `--limit N`, `--since`/`--until` (window in the dataset's own time), and
`--speed X`. The default slice is 100,000 to 1,000,000 events. Benchmarks record the exact flags.

**Event time is rebased to the wall clock.** Dataset timestamps can be years old, which would fall outside retention
and the detector's recent window. The replayer maps the dataset's first timestamp to now and keeps the relative
spacing divided by `--speed`. The original timestamp is kept in `attrs.originalTime`.

### Mapping

The fraud datasets become `com.example.payment.v1` events: `amount` and `label` are hot columns, the remaining
features go into `attrs`.

### Synthetic generator

Normal traffic with seasonality plus injected anomalies:

| Anomaly | Example |
|---|---|
| Latency spike | One model's `latencyMs` jumps |
| Cost jump | One customer's `costUsd` per request multiplies |
| Error burst | `status=error` ratio rises for one source |

The same seed gives the same events and the same `manifest.json` (start, end, dimension, type of every anomaly). The
evaluation harness scores the agent against it.

### Adding an adapter

An adapter yields `(time, id, source, type, data)` tuples. The replayer handles pacing, batching, retries, and
CloudEvents formatting. An adapter needs a download script and a license note in `tools/replayer/README.md`.
