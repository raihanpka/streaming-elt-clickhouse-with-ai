-- GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.
-- Silver layer: parsed and validated events, plus rejected rows with a reason.
CREATE TABLE IF NOT EXISTS bromo.events
(
    `event_id` String COMMENT 'CloudEvents id, unique per source',
    `source` LowCardinality(String) COMMENT 'CloudEvents source: the system that produced the event',
    `type` LowCardinality(String) COMMENT 'CloudEvents type, for example com.example.payment.v1',
    `time` DateTime64(3, 'UTC') COMMENT 'Event time chosen by the producer, UTC. Queries use this column.',
    `ingested_at` DateTime64(3, 'UTC') COMMENT 'When the ingest API stored the event, UTC',
    `subject` String COMMENT 'Optional CloudEvents subject, for example a card or customer id',
    `amount` Nullable(Float64) COMMENT 'Numeric amount from data.amount, NULL when the event has none',
    `label` LowCardinality(String) COMMENT 'Category from data.label, for example fraud or ok, empty when absent',
    `attrs` Map(String, String) COMMENT 'Remaining fields of data as text, excluding the promoted fields'
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(time)
ORDER BY (type, source, toStartOfMinute(time), event_id)
TTL toDateTime(time) + INTERVAL 90 DAY
COMMENT 'Silver: validated events. Duplicates with the same key collapse on merge, keeping the latest ingested row.';

CREATE TABLE IF NOT EXISTS bromo.events_rejected
(
    `request_id` UUID COMMENT 'Id of the ingest request that stored the raw row',
    `reason` LowCardinality(String) COMMENT 'Why the row was rejected: invalid_json, missing_id, missing_source, missing_type, bad_time',
    `payload` String COMMENT 'The raw payload that was rejected',
    `ingested_at` DateTime64(3, 'UTC') COMMENT 'When the raw row was stored, UTC'
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ingested_at)
ORDER BY (ingested_at, request_id)
TTL toDateTime(ingested_at) + INTERVAL 30 DAY
COMMENT 'Silver: raw rows that failed validation, with a reason. Nothing is dropped silently.';

CREATE MATERIALIZED VIEW IF NOT EXISTS bromo.events_mv TO bromo.events
DEFINER = admin SQL SECURITY DEFINER
COMMENT 'Silver: parses valid raw rows into events'
AS SELECT
    JSONExtractString(payload, 'id') AS event_id,
    JSONExtractString(payload, 'source') AS source,
    JSONExtractString(payload, 'type') AS type,
    assumeNotNull(parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC')) AS time,
    ingested_at,
    JSONExtractString(payload, 'subject') AS subject,
    JSONExtract(payload, 'data', 'amount', 'Nullable(Float64)') AS amount,
    JSONExtractString(payload, 'data', 'label') AS label,
    mapFilter((k, v) -> k NOT IN ('amount', 'label'), JSONExtract(payload, 'data', 'Map(String, String)')) AS attrs
FROM bromo.raw
WHERE isValidJSON(payload)
  AND JSONExtractString(payload, 'id') != ''
  AND JSONExtractString(payload, 'source') != ''
  AND JSONExtractString(payload, 'type') != ''
  AND parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC') IS NOT NULL;

CREATE MATERIALIZED VIEW IF NOT EXISTS bromo.events_rejected_mv TO bromo.events_rejected
DEFINER = admin SQL SECURITY DEFINER
COMMENT 'Silver: routes invalid raw rows to events_rejected with a reason'
AS SELECT
    request_id,
    multiIf(
        NOT isValidJSON(payload), 'invalid_json',
        JSONExtractString(payload, 'id') = '', 'missing_id',
        JSONExtractString(payload, 'source') = '', 'missing_source',
        JSONExtractString(payload, 'type') = '', 'missing_type',
        'bad_time') AS reason,
    payload,
    ingested_at
FROM bromo.raw
WHERE NOT (isValidJSON(payload)
  AND JSONExtractString(payload, 'id') != ''
  AND JSONExtractString(payload, 'source') != ''
  AND JSONExtractString(payload, 'type') != ''
  AND parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC') IS NOT NULL);
