-- GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.
-- Bronze layer: exactly what the ingest API stored, one row per event.
CREATE TABLE IF NOT EXISTS bromo.raw
(
    `request_id` UUID COMMENT 'Id of the ingest request that stored this row, also present in logs',
    `payload` String COMMENT 'The event JSON exactly as received, unmodified',
    `ingested_at` DateTime64(3, 'UTC') DEFAULT now64(3) COMMENT 'When the row was stored, UTC'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ingested_at)
ORDER BY (ingested_at, request_id)
TTL toDateTime(ingested_at) + INTERVAL 30 DAY
COMMENT 'Bronze: raw events as stored by the ingest API. Read only by the silver views and by reprocessing.';
