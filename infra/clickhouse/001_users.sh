#!/bin/bash
# GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.
set -euo pipefail

: "${CLICKHOUSE_INGEST_PASSWORD:?set CLICKHOUSE_INGEST_PASSWORD}"
: "${CLICKHOUSE_API_PASSWORD:?set CLICKHOUSE_API_PASSWORD}"

clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --multiquery <<SQL
CREATE DATABASE IF NOT EXISTS bronze COMMENT 'Bronze layer: events exactly as received';
CREATE DATABASE IF NOT EXISTS silver COMMENT 'Silver layer: parsed, typed, and validated events';
CREATE DATABASE IF NOT EXISTS gold COMMENT 'Gold layer: per minute rollups read by the API, the detector, and the UI';

CREATE USER IF NOT EXISTS ingest_writer IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_INGEST_PASSWORD}';
CREATE USER IF NOT EXISTS api_reader IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_API_PASSWORD}';

GRANT INSERT ON bronze.events TO ingest_writer;
GRANT SELECT ON gold.* TO api_reader;
SQL
