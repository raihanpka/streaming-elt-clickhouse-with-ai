# Replayer

Replays a slice of a dataset through the Bromo ingest API as CloudEvents. The pipeline cannot tell a replay from live traffic, so replays exercise the real path and labels in the data give ground truth.

## Quick start

```bash
cd tools/replayer
uv venv .venv && uv pip install --python .venv/bin/python -e ".[dev]"

# Without the real dataset: a synthetic file with the same columns (random data, not the real dataset)
.venv/bin/python scripts/make_sample_csv.py --rows 100000 --out data/synthetic-ulb-like.csv

export BROMO_API_KEY=...        # the same key as the app
.venv/bin/replay --mapping datasets/ulb-fraud.yml --file data/synthetic-ulb-like.csv --limit 100000 --speed 100
```

Flags: `--limit N`, `--since` and `--until` (dataset time, same scale as the time column), `--speed X` (1 is the original
pace), `--batch-size N`, `--url`, `--api-key`, `--dry-run`, `--summary FILE`. The run prints `planned` and `sent` counts
and writes them to `replay-summary.json`.

## Sources

| Source | Use it when | Needs |
|---|---|---|
| CSV file | The data is a local file | `--file` |
| ClickHouse query | The data lives in ClickHouse or a public bucket it can read (`s3()`, `url()`), or the slice must be sorted and filtered by the server | `--source-url`, `--source-user`, `--source-password`, optional `--param KEY=VALUE` |

A ClickHouse source puts a `source.query` in the mapping. It uses `{since}`, `{until}`, `{limit}` and the keys under
`source.params` as placeholders, returns rows sorted by the time column, and leaves `FORMAT` out. Defaults read the
`REPLAYER_SOURCE_URL`, `REPLAYER_SOURCE_USER`, and `REPLAYER_SOURCE_PASSWORD` variables, or `CLICKHOUSE_ADMIN_PASSWORD`.
For a public playground such as `https://play.clickhouse.com` use user `explorer` with an empty password.

Rows are streamed in batches, so a slice of millions of rows does not sit in memory. Empty and null values are left out
of the event data.

## How it works

- **Time is rebased to the wall clock.** The first event gets the current time and the spacing of the rest is divided by
  `--speed`. Events are never stamped in the future. The original value is kept in `data.originalTime`.
- **Ids are stable.** The id depends only on the row, so a re-run sends the same ids and the warehouse deduplicates.
- **A re-run is a new run.** The ids repeat, but event times are rebased again, so silver treats the events as new
  (its key includes the event minute). Only a retried batch, which carries identical times, collapses.
- **Retries are safe.** A `503`, `429`, or connection error is retried with backoff. A `400` stops the run.
- **Nothing is committed.** Datasets are downloaded by you. Check each license first.

## Adding a dataset

Copy `datasets/ulb-fraud.yml` (CSV) or `datasets/ontime-flights.yml` (ClickHouse query), set the time column and unit,
and map the columns to the keys that the `fields` of your data contract expect. No code changes are needed for another
CSV or query.

## Reconciliation

After a run, compare the `sent` count with the distinct ids in silver: `infra/clickhouse/maintenance/run.sh count card-replay`.
The full layer-to-layer checks are `infra/clickhouse/maintenance/run.sh reconcile`.

## Tests

```bash
.venv/bin/python -m pytest
.venv/bin/ruff check . && .venv/bin/ruff format --check .
```

## License of the example dataset

The ULB credit card fraud dataset is on Kaggle: https://www.kaggle.com/datasets/mlg-ulb/creditcardfraud. Read its terms
before redistributing anything derived from it.
