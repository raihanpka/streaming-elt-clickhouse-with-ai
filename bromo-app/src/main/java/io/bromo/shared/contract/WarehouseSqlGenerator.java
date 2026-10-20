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
    files.put("002_bronze.sql", bronze(c));
    files.put("003_silver.sql", silver(c));
    files.put("004_gold.sql", gold(c));
    files.put("maintenance/reprocess.sql", reprocess(c));
    files.put("maintenance/reconcile.sql", reconcile(c));
    files.put("tests/contract-examples.sh", examples(c));
    return files;
  }

  /** The shell script that creates the layer databases, users, and grants. */
  private static String users(DataContract c) {
    DataContract.Layers l = c.layers();
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
            + l.bronze()
            + " COMMENT 'Bronze layer: events exactly as received';",
        "CREATE DATABASE IF NOT EXISTS "
            + l.silver()
            + " COMMENT 'Silver layer: parsed, typed, and validated events';",
        "CREATE DATABASE IF NOT EXISTS "
            + l.gold()
            + " COMMENT 'Gold layer: per minute rollups read by the API, the detector, and the UI';",
        "",
        "CREATE USER IF NOT EXISTS ingest_writer IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_INGEST_PASSWORD}';",
        "CREATE USER IF NOT EXISTS api_reader IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_API_PASSWORD}';",
        "",
        "GRANT INSERT ON " + c.bronzeTable() + " TO ingest_writer;",
        "GRANT SELECT ON " + l.gold() + ".* TO api_reader;",
        "SQL",
        "");
  }

  /** The bronze table. */
  private static String bronze(DataContract c) {
    return String.join(
        "\n",
        "-- " + HEADER,
        "-- Bronze layer: exactly what the ingest API stored, one row per event.",
        "CREATE TABLE IF NOT EXISTS " + c.bronzeTable(),
        "(",
        "    `request_id` UUID COMMENT 'Id of the ingest request that stored this row, also present in logs',",
        "    `payload` String COMMENT 'The event JSON exactly as received, unmodified',",
        "    `ingested_at` DateTime64(3, 'UTC') DEFAULT now64(3) COMMENT 'When the row was stored, UTC'",
        ")",
        "ENGINE = MergeTree",
        "PARTITION BY toYYYYMM(ingested_at)",
        "ORDER BY (ingested_at, request_id)",
        "TTL toDateTime(ingested_at) + INTERVAL " + c.retentionDays().bronze() + " DAY",
        "COMMENT 'Bronze: events exactly as the ingest API stored them. Read only by the silver views and by reprocessing.';",
        "");
  }

  /** The silver tables and the two views that fill them from bronze. */
  private static String silver(DataContract c) {
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

    List<String> selects = silverSelects(c);

    String valid = validPredicate();
    return String.join(
        "\n",
        "-- " + HEADER,
        "-- Silver layer: parsed and validated events, plus rejected rows with a reason.",
        "CREATE TABLE IF NOT EXISTS " + c.silverTable(),
        "(",
        String.join(",\n", columns),
        ")",
        "ENGINE = ReplacingMergeTree(ingested_at)",
        "PARTITION BY toYYYYMM(time)",
        "ORDER BY (type, source, toStartOfMinute(time), event_id)",
        "TTL toDateTime(time) + INTERVAL " + c.retentionDays().silver() + " DAY",
        "COMMENT 'Silver: validated events. Duplicates with the same key collapse on merge, keeping the latest ingested row.';",
        "",
        "CREATE TABLE IF NOT EXISTS " + c.silverRejectedTable(),
        "(",
        column("request_id", "UUID", "Id of the ingest request that stored the bronze row") + ",",
        column(
                "reason",
                "LowCardinality(String)",
                "Why the row was rejected: invalid_json, missing_id, missing_source, missing_type, bad_time")
            + ",",
        column("payload", "String", "The payload that was rejected") + ",",
        column("ingested_at", "DateTime64(3, 'UTC')", "When the bronze row was stored, UTC"),
        ")",
        "ENGINE = MergeTree",
        "PARTITION BY toYYYYMM(ingested_at)",
        "ORDER BY (ingested_at, request_id)",
        "TTL toDateTime(ingested_at) + INTERVAL " + c.retentionDays().silverRejected() + " DAY",
        "COMMENT 'Silver: raw rows that failed validation, with a reason. Nothing is dropped silently.';",
        "",
        "CREATE MATERIALIZED VIEW IF NOT EXISTS " + c.silverTable() + "_mv TO " + c.silverTable(),
        "DEFINER = admin SQL SECURITY DEFINER",
        "COMMENT 'Silver: parses valid raw rows into events'",
        "AS SELECT",
        String.join(",\n", selects),
        "FROM " + c.bronzeTable(),
        "WHERE " + valid + ";",
        "",
        "CREATE MATERIALIZED VIEW IF NOT EXISTS "
            + c.silverRejectedTable()
            + "_mv TO "
            + c.silverRejectedTable(),
        "DEFINER = admin SQL SECURITY DEFINER",
        "COMMENT 'Silver: routes invalid raw rows to events_rejected with a reason'",
        "AS SELECT",
        rejectedSelects(),
        "FROM " + c.bronzeTable(),
        "WHERE NOT (" + valid + ");",
        "");
  }

  /** The gold rollup tables and their views, one per mart in the contract. */
  private static String gold(DataContract c) {
    List<String> parts = new ArrayList<>();
    parts.add("-- " + HEADER);
    parts.add("-- Gold layer: per minute rollups read by the API, the detector, and the UI.");
    for (DataContract.GoldTableSpec spec : c.goldTables()) {
      parts.add(goldTable(c, spec));
    }
    return String.join("\n", parts) + "\n";
  }

  /** One gold table and the view that fills it from silver. */
  private static String goldTable(DataContract c, DataContract.GoldTableSpec spec) {
    String table = c.goldTable(spec);
    Map<String, FieldSpec> byName = new LinkedHashMap<>();
    c.fields().forEach(f -> byName.put(f.name(), f));
    List<String> dimensions = spec.dimensions();

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
    for (Measure m : spec.measures()) {
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
        "",
        "CREATE TABLE IF NOT EXISTS " + table,
        "(",
        String.join(",\n", columns),
        ")",
        "ENGINE = AggregatingMergeTree",
        "PARTITION BY toYYYYMM(minute)",
        "ORDER BY (" + String.join(", ", orderBy) + ")",
        "TTL minute + INTERVAL " + c.retentionDays().gold() + " DAY",
        "COMMENT 'Gold: per minute rollup of events. Read counters with sum(), states with the -Merge functions.';",
        "",
        "CREATE MATERIALIZED VIEW IF NOT EXISTS " + table + "_mv TO " + table,
        "DEFINER = admin SQL SECURITY DEFINER",
        "COMMENT 'Gold: aggregates new events into " + spec.name() + "'",
        "AS SELECT",
        String.join(",\n", selects),
        "FROM " + c.silverTable(),
        "GROUP BY " + String.join(", ", groupBy) + ";");
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
        "BRONZE=" + shell(c.layers().bronze()),
        "SILVER=" + shell(c.layers().silver()),
        "GOLD=" + shell(c.layers().gold()),
        "BRONZE_TABLE=" + shell(c.bronzeTable()),
        "SILVER_TABLE=" + shell(c.silverTable()),
        "SILVER_REJECTED_TABLE=" + shell(c.silverRejectedTable()),
        "GOLD_TABLES=("
            + c.goldTables().stream()
                .map(m -> shell(c.goldTable(m)))
                .collect(Collectors.joining(" "))
            + ")",
        "SAMPLE_TYPE=" + shell(s.type()),
        "SAMPLE_SOURCE=" + shell(s.source()),
        "SAMPLE_SUBJECT=" + shell(s.subject() == null ? "" : s.subject()),
        "SAMPLE_DATA=" + shell(MAPPER.writeValueAsString(s.data())),
        "SAMPLE_FIELD_CHECKS=(" + String.join(" ", fieldChecks) + ")",
        "SAMPLE_ATTR_CHECKS=(" + String.join(" ", attrChecks) + ")",
        "");
  }

  /**
   * The select list that turns a bronze row into a silver event, shared by the view and the
   * rebuild.
   */
  private static List<String> silverSelects(DataContract c) {
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
    return selects;
  }

  /** The select list that turns an invalid bronze row into a rejected row with a reason. */
  private static String rejectedSelects() {
    return String.join(
        "\n",
        "    request_id,",
        "    multiIf(",
        "        NOT isValidJSON(payload), 'invalid_json',",
        "        JSONExtractString(payload, 'id') = '', 'missing_id',",
        "        JSONExtractString(payload, 'source') = '', 'missing_source',",
        "        JSONExtractString(payload, 'type') = '', 'missing_type',",
        "        'bad_time') AS reason,",
        "    payload,",
        "    ingested_at");
  }

  /** The condition that separates valid bronze rows from rejected ones. */
  private static String validPredicate() {
    return String.join(
        "\n  AND ",
        "isValidJSON(payload)",
        "JSONExtractString(payload, 'id') != ''",
        "JSONExtractString(payload, 'source') != ''",
        "JSONExtractString(payload, 'type') != ''",
        parsedTime() + " IS NOT NULL");
  }

  /**
   * The script that rebuilds silver and gold from bronze. Inserting into silver fires the gold
   * views.
   */
  private static String reprocess(DataContract c) {
    List<String> lines = new ArrayList<>();
    lines.add("-- " + HEADER);
    lines.add(
        "-- Rebuilds silver and gold from bronze. Bronze is not touched. Run with the admin user:");
    lines.add("--   infra/clickhouse/maintenance/run.sh reprocess");
    for (DataContract.GoldTableSpec spec : c.goldTables()) {
      lines.add("TRUNCATE TABLE " + c.goldTable(spec) + ";");
    }
    lines.add("TRUNCATE TABLE " + c.silverRejectedTable() + ";");
    lines.add("TRUNCATE TABLE " + c.silverTable() + ";");
    lines.add("");
    lines.add("-- The gold views fire on this insert, so every gold table is rebuilt too.");
    lines.add("INSERT INTO " + c.silverTable());
    lines.add("SELECT");
    lines.add(String.join(",\n", silverSelects(c)));
    lines.add("FROM " + c.bronzeTable());
    lines.add("WHERE " + validPredicate() + ";");
    lines.add("");
    lines.add("INSERT INTO " + c.silverRejectedTable());
    lines.add("SELECT");
    lines.add(rejectedSelects());
    lines.add("FROM " + c.bronzeTable());
    lines.add("WHERE NOT (" + validPredicate() + ");");
    return String.join("\n", lines) + "\n";
  }

  /**
   * The queries that compare each layer with the one before it. Every row has consistent = 1 when
   * healthy.
   */
  private static String reconcile(DataContract c) {
    List<String> checks = new ArrayList<>();
    checks.add(
        check(
            "bronze_valid_vs_silver",
            "(SELECT uniqExact(JSONExtractString(payload, 'source'), JSONExtractString(payload, 'id')) FROM "
                + c.bronzeTable()
                + " WHERE "
                + validPredicate()
                + ")",
            "(SELECT uniqExact(source, event_id) FROM " + c.silverTable() + ")",
            "expected = actual"));
    checks.add(
        check(
            "bronze_invalid_vs_rejected",
            "(SELECT count() FROM " + c.bronzeTable() + " WHERE NOT (" + validPredicate() + "))",
            "(SELECT count() FROM " + c.silverRejectedTable() + ")",
            "expected = actual"));
    for (DataContract.GoldTableSpec spec : c.goldTables()) {
      checks.add(
          check(
              "silver_vs_" + spec.name(),
              "(SELECT count() FROM " + c.silverTable() + ")",
              "(SELECT sum(events) FROM " + c.goldTable(spec) + ")",
              "actual >= expected"));
    }
    return "-- "
        + HEADER
        + "\n-- One row per check. consistent = 1 is healthy. Gold may exceed silver by the number of duplicates"
        + "\n-- that silver already merged, which is the documented over-count.\n"
        + String.join("\nUNION ALL\n", checks)
        + "\n";
  }

  /** One reconciliation row. */
  private static String check(String name, String expected, String actual, String rule) {
    return "SELECT '"
        + name
        + "' AS check, "
        + expected
        + " AS expected, "
        + actual
        + " AS actual, toUInt8("
        + rule
        + ") AS consistent";
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
