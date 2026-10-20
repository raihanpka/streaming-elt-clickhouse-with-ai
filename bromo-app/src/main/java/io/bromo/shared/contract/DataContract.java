package io.bromo.shared.contract;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The parsed data contract: the single description of the events, the warehouse, and the metrics.
 */
public record DataContract(
    int version,
    String database,
    Retention retentionDays,
    EventRules event,
    List<FieldSpec> fields,
    MartSpec mart,
    List<MetricSpec> metrics,
    PlainJsonIntake plainJson,
    Sample sample) {

  /** Days each warehouse layer keeps data. */
  public record Retention(int raw, int events, int rejected, int mart) {}

  /** Rules for the CloudEvents envelope. */
  public record EventRules(
      Pattern typePattern, int maxIdLength, int maxSourceLength, int futureToleranceMinutes) {}

  /** A field promoted out of the event data into a typed column. */
  public record FieldSpec(
      String name, String path, FieldType type, boolean nullable, String description) {}

  /** Numeric aggregation of one field in the mart. */
  public record Measure(String field, List<String> functions) {}

  /** The per minute rollup: extra dimensions and measures. */
  public record MartSpec(List<String> dimensions, List<Measure> measures) {}

  /** A metric exposed by the API and described to the AI agent. */
  public record MetricSpec(
      String name, String unit, String description, String expression, List<String> breakdowns) {}

  /** Where a plain JSON event attribute comes from: a JSON Pointer or a constant. */
  public record Binding(String pointer, String constant) {}

  /** Mapping for accepting JSON that is not CloudEvents. */
  public record PlainJsonIntake(
      Binding id, Binding time, Binding source, Binding type, Binding subject) {}

  /** A valid example event used by tests. */
  public record Sample(String type, String source, String subject, Map<String, Object> data) {}
}
