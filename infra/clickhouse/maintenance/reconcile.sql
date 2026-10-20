-- GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.
-- One row per check. consistent = 1 is healthy. Gold may exceed silver by the number of duplicates
-- that silver already merged, which is the documented over-count.
SELECT 'bronze_valid_vs_silver' AS check, (SELECT uniqExact(JSONExtractString(payload, 'source'), JSONExtractString(payload, 'id')) FROM bronze.events WHERE isValidJSON(payload)
  AND JSONExtractString(payload, 'id') != ''
  AND JSONExtractString(payload, 'source') != ''
  AND JSONExtractString(payload, 'type') != ''
  AND parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC') IS NOT NULL) AS expected, (SELECT uniqExact(source, event_id) FROM silver.events) AS actual, toUInt8(expected = actual) AS consistent
UNION ALL
SELECT 'bronze_invalid_vs_rejected' AS check, (SELECT count() FROM bronze.events WHERE NOT (isValidJSON(payload)
  AND JSONExtractString(payload, 'id') != ''
  AND JSONExtractString(payload, 'source') != ''
  AND JSONExtractString(payload, 'type') != ''
  AND parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC') IS NOT NULL)) AS expected, (SELECT count() FROM silver.events_rejected) AS actual, toUInt8(expected = actual) AS consistent
UNION ALL
SELECT 'silver_vs_events_1m' AS check, (SELECT count() FROM silver.events) AS expected, (SELECT sum(events) FROM gold.events_1m) AS actual, toUInt8(actual >= expected) AS consistent
