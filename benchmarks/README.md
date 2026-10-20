# Benchmarks

Reproducible measurements for Bromo. No benchmark has been run yet. Numbers appear here only with the exact command,
the hardware, and the versions used.

## Rules

1. Record hardware (CPU, cores, RAM, disk, OS), software versions, and the dataset slice flags for every run.
2. Provide the command that reproduces the number. A result without a command is not accepted.
3. Run each test three times. Report the median and the spread.
4. Publish poor results with analysis. Do not delete them.
5. Raw output is committed next to the summary. Nothing is summarized without its source data.

## Planned measurements

| Area | Question | Status |
|---|---|---|
| Resource baseline | Idle RAM and disk of the stack | measured |
| Ingest to queryable | p50 and p95 latency on a 100k-event slice | measured |
| Query time | Endpoint latency on gold tables, from `system.query_log` | planned |
| Agent evaluation | Recall, precision, time to finding, explanation hit, cost per finding, per model | planned |
| Throughput and failures | Ingest ceiling on the reference machine, duplicate rate, recovery after restart | planned |

## Results

### Resource baseline, ClickHouse idle

| Item | Value |
|---|---|
| Machine | Apple M3, 8 cores, 16 GB RAM, OrbStack Docker VM with 8 CPUs and about 6 GB RAM |
| Image | `clickhouse/clickhouse-server:26.3.56.2`, architecture `arm64` (checked with `docker image inspect`) |
| Image size | 1.106 GB |
| Idle memory | about 197 MiB of the 2 GiB limit, CPU about 4 percent just after start |
| Volume | about 100 kB when empty |
| Health | `curl localhost:8123/ping` returns `Ok.` |

Commands:

```bash
docker compose --env-file .env -f infra/docker/docker-compose.yml up -d
docker stats --no-stream bromo-clickhouse
docker system df
```

### Ingest to queryable latency, single node

| Item | Value |
|---|---|
| Machine | Apple M3, 8 cores, 16 GB RAM, OrbStack Docker VM, ClickHouse `26.3.56.2` in a Testcontainers container |
| Path | HTTP request to the ingest API, async insert that waits for the flush, materialized views, then a count query |
| Single event, 200 requests | p50 69.4 ms, p95 74.0 ms, max 103.0 ms (request start until the row is queryable) |
| Batch of 500 events, 10 batches | p50 72.3 ms, p95 93.5 ms |
| Batch throughput, including the verification query | about 6,600 events per second |
| Goal | p95 under 5 seconds, met by a wide margin |

Command: `./gradlew :bromo-app:test --tests '*IngestLatencyTest*'`. The report is written to
`bromo-app/build/reports/ingest-latency.txt`. These numbers come from one run on a laptop, not from the VPS, and are
sequential single client measurements. Treat them as a baseline, not as a capacity claim.

### Replay of 100k events and layer rebuild

| Item | Value |
|---|---|
| Machine | Apple M3, 8 cores, 16 GB RAM, OrbStack Docker VM, ClickHouse `26.3.56.2`, app started with `./gradlew :bromo-app:bootRun` |
| Data | 100,000 **synthetic** rows with the columns of the ULB fraud dataset (random values, not the real dataset), made by `tools/replayer/scripts/make_sample_csv.py` |
| Replay | 100,000 events in 500 batches of 200, `--speed 2000`, finished in 37 s wall time. The run is paced by the dataset timeline, so this is not a maximum throughput. |
| Reconciliation after the replay | bronze valid versus silver 100,000 to 100,000, rejected 0 to 0, silver versus gold 100,000 to 100,000, all consistent |
| Rebuild of silver and gold from bronze | 0.31 s for 100,000 rows, totals and reconciliation identical afterwards |
| ClickHouse memory after the load | about 700 MiB of the 2 GiB limit (includes caches), a single run |

Commands:

```bash
docker compose --env-file .env -f infra/docker/docker-compose.yml up -d
set -a; . ./.env; set +a; ./gradlew :bromo-app:bootRun &
cd tools/replayer
.venv/bin/python scripts/make_sample_csv.py --rows 100000 --out data/synthetic-ulb-like.csv
.venv/bin/replay --mapping datasets/ulb-fraud.yml --file data/synthetic-ulb-like.csv --limit 100000 --speed 2000
cd ../.. && infra/clickhouse/maintenance/run.sh count card-replay
infra/clickhouse/maintenance/run.sh reconcile
infra/clickhouse/maintenance/run.sh reprocess
```

### Replay of 1M real flights (OnTime)

| Item | Value |
|---|---|
| Machine | Apple M3, 8 cores, 16 GB RAM, OrbStack Docker VM, ClickHouse `26.3.56.2` with a 2 GiB limit, app started with `./gradlew :bromo-app:bootRun` |
| Data | The first 1,000,000 flights of 2022-01-01 to 2022-03-31 from the public OnTime copy, read by the replayer through a ClickHouse query that reads `s3()` |
| Replay | 1,000,000 events in batches of 500, `--speed 50000`, 293 s wall time including the S3 read and the sort, about 3,400 events per second end to end. The replayer is single threaded Python. |
| Reconciliation | bronze valid versus silver 1,000,000 to 1,000,000, rejected 0, silver versus both gold tables 1,000,000 each, all consistent |
| Size on disk | bronze 60 MiB, silver 34 MiB, gold 1 to 2 MiB per table (active parts, before background merges finish) |
| ClickHouse memory after the load | about 1.4 GiB of the 2 GiB limit, a single run. Leave headroom on a small VPS. |

Totals of the slice: 54,784 cancelled flights (35,095 for weather, 17,890 for the airline, 917 for the air system, 882 for
security), 5.44 million carrier delay minutes, 4.24 million late aircraft minutes, 1.98 million air system minutes, 0.80
million weather minutes.

Commands: see [docs/DATA.md](../docs/DATA.md#primary-dataset-ontime-flights) with `--speed 50000`. At that speed the
whole quarter fits in about three replay minutes, so the gold tables are coarse. Use `--speed 1440` for day by day
buckets.

## Layout

```
benchmarks/
  README.md              This file
  agent-eval.md          Agent evaluation results
  runs/<date>-<name>/    One folder per run: REPORT.md, hardware.txt, commands.sh, raw output
```

## Reference environments

- Development laptop: Apple M3, 8 cores, 16 GB RAM.
- Public demo VPS: 12 GB RAM, 2 CPU with ARM architecture.

Measure on both where relevant and label the results.
