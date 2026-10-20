#!/bin/bash
# Creates the database and the users that exist at first start. Passwords come from the environment.
set -euo pipefail

: "${CLICKHOUSE_INGEST_PASSWORD:?set CLICKHOUSE_INGEST_PASSWORD}"
: "${CLICKHOUSE_API_PASSWORD:?set CLICKHOUSE_API_PASSWORD}"

clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --multiquery <<SQL
CREATE DATABASE IF NOT EXISTS bromo COMMENT 'Bromo streaming ELT warehouse: raw, events, marts, findings';

CREATE USER IF NOT EXISTS ingest_writer IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_INGEST_PASSWORD}';
CREATE USER IF NOT EXISTS api_reader IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_API_PASSWORD}';

GRANT INSERT ON bromo.raw TO ingest_writer;
SQL
