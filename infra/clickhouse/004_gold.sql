-- GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.
-- Gold layer: per minute rollups read by the API, the detector, and the UI.

CREATE TABLE IF NOT EXISTS gold.events_1m
(
    `minute` DateTime('UTC') COMMENT 'Start of the minute bucket, UTC',
    `type` LowCardinality(String) COMMENT 'CloudEvents type',
    `source` LowCardinality(String) COMMENT 'CloudEvents source',
    `label` LowCardinality(String) COMMENT 'Category from data.label, for example fraud or ok, empty when absent',
    `events` SimpleAggregateFunction(sum, UInt64) COMMENT 'Number of events in the bucket',
    `amount_sum` SimpleAggregateFunction(sum, Float64) COMMENT 'sum of amount in the bucket',
    `amount_p95_state` AggregateFunction(quantile(0.95), Float64) COMMENT '95th percentile state of amount, read with quantileMerge(0.95)',
    `subjects_state` AggregateFunction(uniq, String) COMMENT 'Distinct subjects state, read with uniqMerge'
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(minute)
ORDER BY (type, source, label, minute)
TTL minute + INTERVAL 365 DAY
COMMENT 'Gold: per minute rollup of events. Read counters with sum(), states with the -Merge functions.';

CREATE MATERIALIZED VIEW IF NOT EXISTS gold.events_1m_mv TO gold.events_1m
DEFINER = admin SQL SECURITY DEFINER
COMMENT 'Gold: aggregates new events into events_1m'
AS SELECT
    toStartOfMinute(time) AS minute,
    type,
    source,
    label,
    count() AS events,
    sumIf(assumeNotNull(amount), isNotNull(amount)) AS amount_sum,
    quantileStateIf(0.95)(assumeNotNull(amount), isNotNull(amount)) AS amount_p95_state,
    uniqState(subject) AS subjects_state
FROM silver.events
GROUP BY minute, type, source, label;
