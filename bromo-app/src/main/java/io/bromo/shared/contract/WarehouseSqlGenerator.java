package io.bromo.shared.contract;

import io.bromo.shared.contract.DataContract.FieldSpec;
import io.bromo.shared.contract.DataContract.Measure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the ClickHouse init scripts and the SQL test inputs from the data contract.
 *
 * <p>CUSTOMIZE: to support a new column type or aggregate, extend {@link FieldType} and the helpers
 * in this class. Everything else is driven by config/data-contract.yml.
 */
public final class WarehouseSqlGenerator {

  private static final String HEADER =
      "GENERATED from config/data-contract.yml by ./gradlew :bromo-app:generateWarehouseSql. Do not edit.";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private WarehouseSqlGenerator() {}

  /** Returns every generated file keyed by its path relative to infra/clickhouse. */
  public static Map<String, String> generate(DataContract c) {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("001_users.sh", users(c));
    files.put("002_raw.sql", raw(c));
    files.put("003_events.sql", events(c));
    files.put("004_marts.sql", marts(c));
    files.put("tests/contract-examples.sh", examples(c));
    return files;
  }

  /** The shell script that creates the database, users, and grants. */
  private static String users(DataContract c) {
    String db = c.database();
    return String.join(
        "\n",
        "#!/bin/bash",
        "# " + HEADER,
        "set -euo pipefail",
        "",
        ": \"${CLICKHOUSE_INGEST_PASSWORD:?set CLICKHOUSE_INGEST_PASSWORD}\"",
        ": \"${CLICKHOUSE_API_PASSWORD:?set CLICKHOUSE_API_PASSWORD}\"",
        "",
        "clickhouse-client --user \"$CLICKHOUSE_USER\" --password \"$CLICKHOUSE_PASSWORD\" --multiquery <<SQL",
        "CREATE DATABASE IF NOT EXISTS "
            + db
            + " COMMENT 'Bromo streaming ELT warehouse: raw, events, marts, findings';",
        "",
        "CREATE USER IF NOT EXISTS ingest_writer IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_INGEST_PASSWORD}';",
        "CREATE USER IF NOT EXISTS api_reader IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_API_PASSWORD}';",
        "",
        "GRANT INSERT ON " + db + ".raw TO ingest_writer;",
        "GRANT SELECT ON " + db + ".mart_* TO api_reader;",
        "SQL",
        "");
  }

  /** The bronze table. */
  private static String raw(DataContract c) {
    return String.join(
        "\n",
        "-- " + HEADER,
        "-- Bronze layer: exactly what the ingest API stored, one row per event.",
        "CREATE TABLE IF NOT EXISTS " + c.database() + ".raw",
        "(",
        "    `request_id` UUID COMMENT 'Id of the ingest request that stored this row, also present in logs',",
        "    `payload` String COMMENT 'The event JSON exactly as received, unmodified',",
        "    `ingested_at` DateTime64(3, 'UTC') DEFAULT now64(3) COMMENT 'When the row was stored, UTC'",
        ")",
        "ENGINE = MergeTree",
        "PARTITION BY toYYYYMM(ingested_at)",
        "ORDER BY (ingested_at, request_id)",
        "TTL toDateTime(ingested_at) + INTERVAL " + c.retentionDays().raw() + " DAY",
        "COMMENT 'Bronze: raw events as stored by the ingest API. Read only by the silver views and by reprocessing.';",
        "");
  }

  /** The silver tables and the two views that fill them from raw. */
  private static String events(DataContract c) {
    String db = c.database();
    List<String> columns = new ArrayList<>();
    columns.add(column("event_id", "String", "CloudEvents id, unique per source"));
    columns.add(
        column(
            "source",
            "LowCardinality(String)",
            "CloudEvents source: the system that produced the event"));
    columns.add(
        column(
            "type",
            "LowCardinality(String)",
            "CloudEvents type, for example com.example.payment.v1"));
    columns.add(
        column(
            "time",
            "DateTime64(3, 'UTC')",
            "Event time chosen by the producer, UTC. Queries use this column."));
    columns.add(
        column("ingested_at", "DateTime64(3, 'UTC')", "When the ingest API stored the event, UTC"));
    columns.add(
        column(
            "subject",
            "String",
            "Optional CloudEvents subject, for example a card or customer id"));
    for (FieldSpec f : c.fields()) {
      columns.add(column(f.name(), columnType(f), f.description()));
    }
    columns.add(
        column(
            "attrs",
            "Map(String, String)",
            "Remaining fields of data as text, excluding the promoted fields"));

    List<String> selects = new ArrayList<>();
    selects.add("    JSONExtractString(payload, 'id') AS event_id");
    selects.add("    JSONExtractString(payload, 'source') AS source");
    selects.add("    JSONExtractString(payload, 'type') AS type");
    selects.add("    assumeNotNull(" + parsedTime() + ") AS time");
    selects.add("    ingested_at");
    selects.add("    JSONExtractString(payload, 'subject') AS subject");
    for (FieldSpec f : c.fields()) {
      selects.add("    " + extract(f) + " AS " + f.name());
    }
    String attrs = "JSONExtract(payload, 'data', 'Map(String, String)')";
    if (!c.fields().isEmpty()) {
      String paths =
          c.fields().stream().map(f -> quote(f.path())).collect(Collectors.joining(", "));
      attrs = "mapFilter((k, v) -> k NOT IN (" + paths + "), " + attrs + ")";
    }
    selects.add("    " + attrs + " AS attrs");

    String valid =
        String.join(
            "\n  AND ",
            "isValidJSON(payload)",
            "JSONExtractString(payload, 'id') != ''",
            "JSONExtractString(payload, 'source') != ''",
            "JSONExtractString(payload, 'type') != ''",
            parsedTime() + " IS NOT NULL");
    return String.join(
        "\n",
        "-- " + HEADER,
        "-- Silver layer: parsed and validated events, plus rejected rows with a reason.",
        "CREATE TABLE IF NOT EXISTS " + db + ".events",
        "(",
        String.join(",\n", columns),
        ")",
        "ENGINE = ReplacingMergeTree(ingested_at)",
        "PARTITION BY toYYYYMM(time)",
        "ORDER BY (type, source, toStartOfMinute(time), event_id)",
        "TTL toDateTime(time) + INTERVAL " + c.retentionDays().events() + " DAY",
        "COMMENT 'Silver: validated events. Duplicates with the same key collapse on merge, keeping the latest ingested row.';",
        "",
        "CREATE TABLE IF NOT EXISTS " + db + ".events_rejected",
        "(",
        column("request_id", "UUID", "Id of the ingest request that stored the raw row") + ",",
        column(
                "reason",
                "LowCardinality(String)",
                "Why the row was rejected: invalid_json, missing_id, missing_source, missing_type, bad_time")
            + ",",
        column("payload", "String", "The raw payload that was rejected") + ",",
        column("ingested_at", "DateTime64(3, 'UTC')", "When the raw row was stored, UTC"),
        ")",
        "ENGINE = MergeTree",
        "PARTITION BY toYYYYMM(ingested_at)",
        "ORDER BY (ingested_at, request_id)",
        "TTL toDateTime(ingested_at) + INTERVAL " + c.retentionDays().rejected() + " DAY",
        "COMMENT 'Silver: raw rows that failed validation, with a reason. Nothing is dropped silently.';",
        "",
        "CREATE MATERIALIZED VIEW IF NOT EXISTS " + db + ".events_mv TO " + db + ".events",
        "DEFINER = admin SQL SECURITY DEFINER",
        "COMMENT 'Silver: parses valid raw rows into events'",
        "AS SELECT",
        String.join(",\n", selects),
        "FROM " + db + ".raw",
        "WHERE " + valid + ";",
        "",
        "CREATE MATERIALIZED VIEW IF NOT EXISTS "
            + db
            + ".events_rejected_mv TO "
            + db
            + ".events_rejected",
        "DEFINER = admin SQL SECURITY DEFINER",
        "COMMENT 'Silver: routes invalid raw rows to events_rejected with a reason'",
        "AS SELECT",
        "    request_id,",
        "    multiIf(",
        "        NOT isValidJSON(payload), 'invalid_json',",
        "        JSONExtractString(payload, 'id') = '', 'missing_id',",
        "        JSONExtractString(payload, 'source') = '', 'missing_source',",
        "        JSONExtractString(payload, 'type') = '', 'missing_type',",
        "        'bad_time') AS reason,",
        "    payload,",
        "    ingested_at",
        "FROM " + db + ".raw",
        "WHERE NOT (" + valid + ");",
        "");
  }

  /** The gold rollup table and its view. */
  private static String marts(DataContract c) {
    String db = c.database();
    Map<String, FieldSpec> byName = new LinkedHashMap<>();
    c.fields().forEach(f -> byName.put(f.name(), f));
    List<String> dimensions = c.mart().dimensions();

    List<String> columns = new ArrayList<>();
    columns.add(column("minute", "DateTime('UTC')", "Start of the minute bucket, UTC"));
    columns.add(column("type", "LowCardinality(String)", "CloudEvents type"));
    columns.add(column("source", "LowCardinality(String)", "CloudEvents source"));
    List<String> selects = new ArrayList<>();
    selects.add("    toStartOfMinute(time) AS minute");
    selects.add("    type");
    selects.add("    source");
    for (String d : dimensions) {
      FieldSpec f = byName.get(d);
      columns.add(column(d, f.type().clickhouseType(), f.description()));
      selects.add("    " + d);
    }
    columns.add(
        column("events", "SimpleAggregateFunction(sum, UInt64)", "Number of events in the bucket"));
    selects.add("    count() AS events");
    for (Measure m : c.mart().measures()) {
      FieldSpec f = byName.get(m.field());
      String t = f.type().clickhouseType();
      for (String fn : m.functions()) {
        String name = m.field() + "_" + fn;
        String source = f.nullable() ? "assumeNotNull(" + m.field() + ")" : m.field();
        String cond = f.nullable() ? "isNotNull(" + m.field() + ")" : null;
        if ("p95".equals(fn)) {
          columns.add(
              column(
                  name + "_state",
                  "AggregateFunction(quantile(0.95), " + t + ")",
                  "95th percentile state of " + m.field() + ", read with quantileMerge(0.95)"));
          selects.add(
              "    "
                  + (cond == null
                      ? "quantileState(0.95)(" + source + ")"
                      : "quantileStateIf(0.95)(" + source + ", " + cond + ")")
                  + " AS "
                  + name
                  + "_state");
        } else {
          columns.add(
              column(
                  name,
                  "SimpleAggregateFunction(" + fn + ", " + t + ")",
                  fn + " of " + m.field() + " in the bucket"));
          selects.add(
              "    "
                  + (cond == null
                      ? fn + "(" + source + ")"
                      : fn + "If(" + source + ", " + cond + ")")
                  + " AS "
                  + name);
        }
      }
    }
    columns.add(
        column(
            "subjects_state",
            "AggregateFunction(uniq, String)",
            "Distinct subjects state, read with uniqMerge"));
    selects.add("    uniqState(subject) AS subjects_state");

    List<String> groupBy = new ArrayList<>(List.of("minute", "type", "source"));
    groupBy.addAll(dimensions);
    List<String> orderBy = new ArrayList<>(List.of("type", "source"));
    orderBy.addAll(dimensions);
    orderBy.add("minute");

    return String.join(
        "\n",
        "-- " + HEADER,
        "-- Gold layer: per minute rollup read by the API, the detector, and the UI.",
        "CREATE TABLE IF NOT EXISTS " + db + ".mart_events_1m",
        "(",
        String.join(",\n", columns),
        ")",
        "ENGINE = AggregatingMergeTree",
        "PARTITION BY toYYYYMM(minute)",
        "ORDER BY (" + String.join(", ", orderBy) + ")",
        "TTL minute + INTERVAL " + c.retentionDays().mart() + " DAY",
        "COMMENT 'Gold: per minute rollup of events. Read counters with sum(), states with the -Merge functions.';",
        "",
        "CREATE MATERIALIZED VIEW IF NOT EXISTS "
            + db
            + ".mart_events_1m_mv TO "
            + db
            + ".mart_events_1m",
        "DEFINER = admin SQL SECURITY DEFINER",
        "COMMENT 'Gold: aggregates new events into the per minute rollup'",
        "AS SELECT",
        String.join(",\n", selects),
        "FROM " + db + ".events",
        "GROUP BY " + String.join(", ", groupBy) + ";",
        "");
  }

  /** The shell variables the SQL tests use, taken from the sample event. */
  private static String examples(DataContract c) {
    DataContract.Sample s = c.sample();
    List<String> fieldChecks = new ArrayList<>();
    List<String> attrChecks = new ArrayList<>();
    Map<String, FieldSpec> byPath = new LinkedHashMap<>();
    c.fields().forEach(f -> byPath.put(f.path(), f));
    s.data()
        .forEach(
            (key, value) -> {
              FieldSpec f = byPath.get(key);
              String text = display(value);
              if (f != null) {
                fieldChecks.add(shell(f.name() + "|" + text));
              } else {
                attrChecks.add(shell(key + "|" + text));
              }
            });
    return String.join(
        "\n",
        "# " + HEADER,
        "DB=" + shell(c.database()),
        "SAMPLE_TYPE=" + shell(s.type()),
        "SAMPLE_SOURCE=" + shell(s.source()),
        "SAMPLE_SUBJECT=" + shell(s.subject() == null ? "" : s.subject()),
        "SAMPLE_DATA=" + shell(MAPPER.writeValueAsString(s.data())),
        "SAMPLE_FIELD_CHECKS=(" + String.join(" ", fieldChecks) + ")",
        "SAMPLE_ATTR_CHECKS=(" + String.join(" ", attrChecks) + ")",
        "");
  }

  /** One column line with its comment. */
  private static String column(String name, String type, String comment) {
    return "    `" + name + "` " + type + " COMMENT " + quote(comment);
  }

  /** The ClickHouse column type of a field. */
  private static String columnType(FieldSpec f) {
    String base = f.type().clickhouseType();
    if (!f.nullable()) {
      return base;
    }
    return f.type() == FieldType.LOW_CARDINALITY_STRING
        ? "LowCardinality(Nullable(String))"
        : "Nullable(" + base + ")";
  }

  /** The expression that reads a field out of the raw payload. */
  private static String extract(FieldSpec f) {
    if (f.type().textual() && !f.nullable()) {
      return "JSONExtractString(payload, 'data', " + quote(f.path()) + ")";
    }
    String type = f.type().textual() ? "String" : f.type().clickhouseType();
    String target = f.nullable() ? "Nullable(" + type + ")" : type;
    return "JSONExtract(payload, 'data', " + quote(f.path()) + ", " + quote(target) + ")";
  }

  /** The expression that parses the event time, NULL when invalid. */
  private static String parsedTime() {
    return "parseDateTime64BestEffortOrNull(JSONExtractString(payload, 'time'), 3, 'UTC')";
  }

  /** A single quoted SQL string literal. */
  private static String quote(String value) {
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  /** A single quoted shell word. */
  private static String shell(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /** How ClickHouse prints a value of the sample. */
  private static String display(Object value) {
    if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
      return String.valueOf(d.longValue());
    }
    return String.valueOf(value);
  }
}
