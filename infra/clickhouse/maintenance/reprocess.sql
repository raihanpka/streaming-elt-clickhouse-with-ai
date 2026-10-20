-- GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.
-- Rebuilds silver and gold from bronze. Bronze is not touched. Run with the admin user:
--   infra/clickhouse/maintenance/run.sh reprocess
TRUNCATE TABLE gold.events_1m;
TRUNCATE TABLE silver.events_rejected;
TRUNCATE TABLE silver.events;

-- The gold views fire on this insert, so every gold table is rebuilt too.
INSERT INTO silver.events
SELECT
    JSONExtractString(payload, 'id') AS event_id,
    JSONExtractString(payload, 'source') AS source,
    JSONExtractString(payload, 'type') AS type,
    assumeNotNull(parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC')) AS time,
    ingested_at,
    JSONExtractString(payload, 'subject') AS subject,
    JSONExtract(payload, 'data', 'amount', 'Nullable(Float64)') AS amount,
    JSONExtractString(payload, 'data', 'label') AS label,
    mapFilter((k, v) -> k NOT IN ('amount', 'label'), JSONExtract(payload, 'data', 'Map(String, String)')) AS attrs
FROM bronze.events
WHERE isValidJSON(payload)
  AND JSONExtractString(payload, 'id') != ''
  AND JSONExtractString(payload, 'source') != ''
  AND JSONExtractString(payload, 'type') != ''
  AND parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC') IS NOT NULL;

INSERT INTO silver.events_rejected
SELECT
    request_id,
    multiIf(
        NOT isValidJSON(payload), 'invalid_json',
        JSONExtractString(payload, 'id') = '', 'missing_id',
        JSONExtractString(payload, 'source') = '', 'missing_source',
        JSONExtractString(payload, 'type') = '', 'missing_type',
        'bad_time') AS reason,
    payload,
    ingested_at
FROM bronze.events
WHERE NOT (isValidJSON(payload)
  AND JSONExtractString(payload, 'id') != ''
  AND JSONExtractString(payload, 'source') != ''
  AND JSONExtractString(payload, 'type') != ''
  AND parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC') IS NOT NULL);
