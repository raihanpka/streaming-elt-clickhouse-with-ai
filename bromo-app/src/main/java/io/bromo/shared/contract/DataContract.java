package io.bromo.shared.contract;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The parsed data contract: the single description of the events, the warehouse, and the metrics.
 */
public record DataContract(
    int version,
    Layers layers,
    Retention retentionDays,
    EventRules event,
    List<FieldSpec> fields,
    List<GoldTableSpec> goldTables,
    List<MetricSpec> metrics,
    PlainJsonIntake plainJson,
    Sample sample) {

  /** Names of the three ClickHouse databases, one per layer. */
  public record Layers(String bronze, String silver, String gold) {}

  /** Days each table group keeps data. */
  public record Retention(int bronze, int silver, int silverRejected, int gold) {}

  /** Rules for the CloudEvents envelope. */
  public record EventRules(
      Pattern typePattern, int maxIdLength, int maxSourceLength, int futureToleranceMinutes) {}

  /** A field promoted out of the event data into a typed column. */
  public record FieldSpec(
      String name, String path, FieldType type, boolean nullable, String description) {}

  /** Numeric aggregation of one field in a gold table. */
  public record Measure(String field, List<String> functions) {}

  /** One per minute table in the gold layer. */
  public record GoldTableSpec(String name, List<String> dimensions, List<Measure> measures) {}

  /** A metric exposed by the API and described to the AI agent. */
  public record MetricSpec(
      String name,
      String goldTable,
      String unit,
      String description,
      String expression,
      List<String> breakdowns) {}

  /** Where a plain JSON event attribute comes from: a JSON Pointer or a constant. */
  public record Binding(String pointer, String constant) {}

  /** Mapping for accepting JSON that is not CloudEvents. */
  public record PlainJsonIntake(
      Binding id, Binding time, Binding source, Binding type, Binding subject) {}

  /** A valid example event used by tests. */
  public record Sample(String type, String source, String subject, Map<String, Object> data) {}

  /** The bronze table that the ingest API writes. */
  public String bronzeTable() {
    return layers.bronze() + ".events";
  }

  /** The silver table of validated events. */
  public String silverTable() {
    return layers.silver() + ".events";
  }

  /** The silver table of rejected rows. */
  public String silverRejectedTable() {
    return layers.silver() + ".events_rejected";
  }

  /** One gold table. */
  public String goldTable(GoldTableSpec table) {
    return layers.gold() + "." + table.name();
  }
}
