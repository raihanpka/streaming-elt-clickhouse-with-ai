# Replayer

Replays a slice of a dataset through the Bromo ingest API as CloudEvents. The pipeline cannot tell a replay from live
traffic, so replays exercise the real path and labels in the data give ground truth.

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

## How it works

- **Time is rebased to the wall clock.** The first event gets the current time and the spacing of the rest is divided by
  `--speed`. Events are never stamped in the future. The original value is kept in `data.originalTime`.
- **Ids are stable.** The id depends only on the row, so a re-run sends the same ids and the warehouse deduplicates.
- **Retries are safe.** A `503`, `429`, or connection error is retried with backoff. A `400` stops the run.
- **Nothing is committed.** Datasets are downloaded by you. Check each license first.

## Adding a dataset

Copy `datasets/ulb-fraud.yml`, set the time column and unit, and map the columns to the keys that the `fields` of
`config/data-contract.yml` expect. No code changes are needed for another CSV.

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
