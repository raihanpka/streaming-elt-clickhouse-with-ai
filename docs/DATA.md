# Data

The ClickHouse warehouse, how data moves through it, the data contract that describes it, and the public datasets used to
demonstrate it.

## Warehouse layers

Bromo uses the medallion layout. Data moves through three layers, and each layer is its own ClickHouse database that
holds several tables. A layer is a stage of quality, not a deployment environment such as dev or prod.

| Layer | Database (default) | What it holds | Tables (default contract) | Written by | Read by |
|---|---|---|---|---|---|
| Bronze | `bronze` | Events exactly as the ingest API received them. Nothing is cleaned or interpreted. | `events` | `bromo-app` ingest as `ingest_writer` | The silver views and rebuilds |
| Silver | `silver` | Events that were parsed, typed, validated, and deduplicated. Rows that failed validation are kept apart with a reason. | `events`, `events_rejected` | Materialized views over bronze | The agent when it drills down, the gold views |
| Gold | `gold` | Business ready per minute tables built for questions: volumes, sums, percentiles, distinct counts. | One table per entry in the contract, for example `events_1m` | Materialized views over silver | The API, the detector, the UI |

### What bronze is

Bronze is the landing zone. It stores the event JSON byte for byte, with the request id and the time it was stored. It
never changes and it keeps its data for a short time. Because nothing is interpreted there, a bug in the silver or gold
logic can never lose data: fix the logic, then rebuild silver and gold from bronze.

### What silver is

Silver is the clean, trustworthy copy. The event JSON is parsed into typed columns (the fields you promote in the data
contract plus a map for everything else), duplicates with the same key collapse on merge, and rows that fail validation
go to `silver.events_rejected` with a reason such as `invalid_json`, `missing_id`, or `bad_time`. Nothing is dropped
silently.

### What gold is

Gold is shaped for use. Each gold table is a per minute rollup over silver with the dimensions and measures you declare
in the contract, so dashboards and the detector read a few thousand rows instead of every event. Read counters with
`sum()` and aggregate states with the `-Merge` functions.

Rule: the API and UI read gold. The agent reads gold and, for drill down, silver. Only rebuilds read bronze.

## The data contract

`config/data-contract.yml` is the one file you edit to adapt Bromo to your own data. It is the single source for:

| Part of the contract | What it controls |
|---|---|
| `layers` | The three database names, for example `shop_bronze`, `shop_silver`, `shop_gold` |
| `retentionDays` | How long each table group keeps data |
| `event` | CloudEvents rules: type pattern, id and source length, future time tolerance |
| `fields` | Which keys of the event `data` become typed silver columns, with type, nullability, and a description |
| `goldTables` | The gold tables: dimensions to group by and numeric measures to aggregate |
| `metrics` | The metrics the API exposes and the agent is told about |
| `intake.plainJson` | Optional mapping to accept JSON that is not CloudEvents |
| `sample` | A valid example event that the tests send |

Descriptions in the contract become ClickHouse column comments. The AI agent reads comments as documentation, so write
them for someone who does not know your data.

Change workflow:

```bash
$EDITOR config/data-contract.yml
./gradlew :bromo-app:generateWarehouseSql     # rewrites infra/clickhouse/00*_*.sql and tests/contract-examples.sh
./gradlew build                                # validator, parser, and generator tests follow the new contract
infra/clickhouse/tests/run.sh                  # starts a throwaway ClickHouse and checks all three layers
```

Generated files carry a header and are never edited by hand. A unit test fails when they are out of date. To test a
different contract without touching the committed one, run `CONTRACT_FILE=my.yml infra/clickhouse/tests/run.sh`.

What follows the contract automatically:

- Ingest validation of the envelope rules and of the types of promoted fields.
- The tables, views, comments, retention, and grants of all three layers.
- The tests: they build events from `sample` and check every promoted field, so they work for any contract.

Existing ClickHouse: set `layers` to your databases, keep the generated SQL as a reference, and point
`CLICKHOUSE_HOST` and `CLICKHOUSE_HTTP_PORT` at your server. The ingest API only needs `INSERT` on the bronze table.
A ready made contract for different data is in `config/examples/`.

Extension points, each marked `CUSTOMIZE` in the code:

| To change | Edit |
|---|---|
| Where accepted events are stored | Provide your own `EventSink` bean (`bromo-app/.../ingest/EventSink.java`) |
| Accept another input format | Add a case in `CloudEventParser.parse` that builds the same CloudEvent shaped tree |
| Accept JSON that is not CloudEvents | `intake.plainJson` in the contract, no code |
| A new column type or aggregate | `FieldType` and `WarehouseSqlGenerator` |
| An extra validation rule | A method in `EventValidator` |

## Conventions

- Names are `snake_case`. Materialized views end in `_mv`. Gold tables are named by the contract, for example
  `events_1m`.
- All timestamps are UTC: `DateTime64(3, 'UTC')`.
- Every table has a `TTL`, a `COMMENT`, and an `ORDER BY` that matches the queries it serves.
- Low cardinality text uses `LowCardinality(String)`. The partition key is monthly, never finer.
- SQL files are generated and idempotent. Changes are additive. A breaking change creates a new table version.

## Tables

The columns below are what the default contract generates. Your contract changes the promoted columns and the gold
tables. Retention values are the contract defaults.

### `bronze.events`

| Column | Type |
|---|---|
| `request_id` | UUID, one per ingest request, also in logs |
| `payload` | String, the event JSON unmodified |
| `ingested_at` | DateTime64(3, 'UTC'), default `now64(3)` |

MergeTree, order by `(ingested_at, request_id)`, partition by `toYYYYMM(ingested_at)`. The ingest API writes with
asynchronous inserts (`async_insert=1`, `wait_for_async_insert=1`) so a response means the data is flushed.
`async_insert_deduplicate=1` makes a retried identical batch a no-op.

### `silver.events`

| Column | Type | Note |
|---|---|---|
| `event_id` | String | CloudEvents `id` |
| `source` | LowCardinality(String) | |
| `type` | LowCardinality(String) | |
| `time` | DateTime64(3, 'UTC') | Event time from the producer |
| `ingested_at` | DateTime64(3, 'UTC') | Carried over from bronze |
| `subject` | String | Optional |
| promoted fields | From the contract | For example `amount Nullable(Float64)` and `label LowCardinality(String)` |
| `attrs` | Map(String, String) | Every other key of `data` as text |

ReplacingMergeTree, order by `(type, source, toStartOfMinute(time), event_id)`, partition by `toYYYYMM(time)`. Rows with
the same key collapse on merge. Rows whose event time is already older than the TTL are dropped at insert, which is why
the API rejects events older than the retention.

### `silver.events_rejected`

`request_id`, `reason`, `payload`, `ingested_at`. Two materialized views read bronze with complementary predicates: rows
that pass validation go to `silver.events`, the rest go here with a computed `reason`. The API already rejects invalid
events with `400`, so this is a second line of defense.

### Gold tables

One AggregatingMergeTree table per `goldTables` entry, fed by a materialized view over `silver.events`. Columns are
`minute`, `type`, `source`, the declared dimensions, `events` (count), one column per measure function (`<field>_sum`,
`<field>_min`, `<field>_max`, `<field>_p95_state`), and `subjects_state` for distinct subjects. Order by `(type, source,
dimensions, minute)`. Add another entry when an endpoint needs a different grouping. Cloudflare's per breakdown minutely
views are the pattern: one view per query shape.

### Agent findings

`agent_findings` is added with the agent: `finding_id`, `created_at`, `kind`, `metric`, `window_start`, `window_end`,
`dimension_name`, `dimension_value`, `severity`, `confidence`, `summary`, `evidence_sql`, `status`, `model`, `tokens`,
`cost_usd`.

## Users and grants

| User | Used by | Rights |
|---|---|---|
| `ingest_writer` | `bromo-app` ingest | `INSERT` on the bronze table only |
| `api_reader` | `bromo-app` read API | `SELECT` on the gold database, row and time limits |
| `agent_reader` | `bromo-agent` | `readonly=1`, `SELECT` on silver `events` and the gold database, `max_execution_time`, `max_rows_to_read`, `max_result_rows`, quota |
| `findings_writer` | `bromo-agent` | `INSERT` on `agent_findings` only |

`agent_reader` has no grant on bronze, `events_rejected`, or system tables. A test proves that `INSERT`, `DROP`, and
`ALTER` fail for it. The materialized views are created with `DEFINER = admin SQL SECURITY DEFINER`, so `ingest_writer`
needs only `INSERT` on bronze. Both behaviors are checked on ClickHouse 26.3 by `infra/clickhouse/tests/t_schema.sh`.

## Delivery guarantees

| Concern | Behavior |
|---|---|
| Delivery | The API acknowledges after the flush. Clients retry on `503`, so delivery is at least once. |
| Ordering | Not guaranteed. Queries use event time. |
| Duplicates | Silver collapses duplicates by key on merge. Gold can over-count by the duplicate rate until a rebuild. The rate is measured and published. A retried identical batch is deduplicated by `async_insert_deduplicate`. |
| Bad data | Goes to `silver.events_rejected` with a reason. Nothing is dropped silently. |
| Backpressure | ClickHouse unavailable: ingest returns `503` and there is no durable buffer. A client that stops retrying loses its events. |
| Replay | Source data is replayed through the ingest API. Silver and gold are rebuilt from bronze with a documented command. |

## Reconciliation

Each layer is comparable with the one before it. These checks live in `infra/clickhouse/tests/` and appear in
`GET /api/v1/pipeline/status`:

| Check | Meaning |
|---|---|
| Rows in bronze versus rows in silver plus rejected | Every bronze row is modeled or rejected |
| Sum of `events` in a gold table versus silver for the same window | Gold matches silver |
| Replayer events sent versus rows in silver after merge | End to end |

A rebuild truncates silver and gold and recreates them from bronze. Totals before and after must match. Backfill never
bypasses the ingest API.

## Datasets

Real data from running banks, insurers, or streaming services is private. Public datasets are used through
**replay**: the replayer reads a slice and sends it as events through the ingest API. The pipeline cannot tell the
difference from live traffic.

> **License notice.** Licenses were not verified when this was written. Check each dataset's page before publishing
> anything derived from it. Datasets are never committed. The replayer downloads them or reads them through ClickHouse.

### Primary dataset: OnTime flights

US domestic flights with delay causes, from the Bureau of Transportation Statistics. ClickHouse hosts a copy in a public
S3 bucket, one gzip CSV per year from 1987 to 2022, roughly five to seven million flights a year. It was chosen because:

- **The explanation is in the data.** From 2005 on, every flight that is 15 minutes or more late carries the minutes by
  cause: airline, weather, national air system, security, late aircraft. An answer such as "delays rose in Texas because
  of weather" can be checked against those columns. Cancellations carry a cause code too.
- **It is analytical.** Airline, origin and destination airport and state, distance, delays, cancellations, and a long
  history with storms and busy seasons.
- **It is safe to serve publicly.** No personal data, only airport codes and aircraft tail numbers.

Contract: `config/examples/ontime-flights.yml` (fields, two gold tables, metrics). Mapping and query:
`tools/replayer/datasets/ontime-flights.yml`. Facts checked on the real files: the file is not sorted by date, so the
query sorts it; `FlightDate` has no time of day, so the event time is the date plus the scheduled departure time (local
time of the origin airport, read as UTC); delay columns are `NULL` or zero for cancelled flights, so the query nulls them;
airport codes are padded with spaces, so the query trims them.

Try it on a clean local stack without touching the repository:

```bash
./gradlew :bromo-app:generateWarehouseSql -PcontractFile=config/examples/ontime-flights.yml -PoutputDir=/tmp/ontime-init
CLICKHOUSE_INIT_DIR=/tmp/ontime-init docker compose --env-file .env -f infra/docker/docker-compose.yml up -d
BROMO_DATA_CONTRACT=config/examples/ontime-flights.yml ./gradlew :bromo-app:bootRun
cd tools/replayer && .venv/bin/replay --mapping datasets/ontime-flights.yml --param year=2022 \
    --since 2022-01-01 --until 2022-03-31 --limit 1000000 --speed 1440
```

### Other datasets

| Dataset | What it is | Size | Labels | Role |
|---|---|---|---|---|
| ULB credit card fraud (Kaggle) | Real European card transactions, PCA-anonymized features, `Time` offset, `Class` | 284,807 rows, 492 fraud | Yes | Fraud story, mapping in `datasets/ulb-fraud.yml` |
| IEEE-CIS fraud (Vesta, Kaggle) | Real e-commerce transactions with card, product, device columns | 590,540 rows, about 3.5% fraud | Yes | Optional, richer dimensions |
| NYC taxi (ClickHouse sample) | Taxi trips with fares and areas | About 3 million rows | No | Optional volume test |
| ClickBench hits | Anonymized web analytics, 105 columns | 100 million rows | No | Optional wide events |
| Synthetic generator | Seasonal traffic with injected anomalies and a manifest | Any | Known anomalies | Scoring the agent when the start of an anomaly must be exact |
| MovieLens, GitHub events, Wikimedia | Ratings, activity, edits | Varies | No | Optional. Check terms first. |

Not used: PaySim (synthetic, and balance columns leak the label). Insurance: no good public transactional dataset was
found, so none is claimed.

### Slicing and time

Large datasets are taken in chunks: `--limit N`, `--since` and `--until`, and `--speed X`. For a ClickHouse query
source these fill the `{since}`, `{until}`, and `{limit}` placeholders of the mapping query, and `--param KEY=VALUE`
fills the others. Parameter values are checked and never trusted. Benchmarks record the exact flags.

**Event time is rebased to the wall clock.** Dataset timestamps can be years old, which would fall outside retention
and the detector's recent window. The replayer maps the first timestamp of the slice to now and keeps the relative
spacing divided by `--speed`. The original value is kept in `attrs.originalTime`.

**Speed decides what a gold minute means.** A gold row is one replay minute. At `--speed 1440` one replay minute is one
day of the dataset, so a month of flights becomes about 30 minutes of replay and the gold tables show day by day
changes. A very high speed squeezes a whole quarter into a few minutes and the gold tables become too coarse.

### Synthetic generator

Normal traffic with seasonality plus injected anomalies:

| Anomaly | Example |
|---|---|
| Latency spike | One model's `latencyMs` jumps |
| Cost jump | One customer's `costUsd` per request multiplies |
| Error burst | `status=error` ratio rises for one source |

The same seed gives the same events and the same `manifest.json` (start, end, dimension, type of every anomaly). The
evaluation harness scores the agent against it.

### Adding a dataset

Copy a mapping from `tools/replayer/datasets/`. A CSV file needs a time column. A ClickHouse source needs a query that
returns the rows sorted by time. The replayer handles pacing, batching, retries, and CloudEvents formatting. Add a
contract under `config/examples/` when the fields differ, and a license note in the mapping file.
