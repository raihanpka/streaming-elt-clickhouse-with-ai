package io.bromo.shared.contract;

import io.bromo.shared.contract.DataContract.Binding;
import io.bromo.shared.contract.DataContract.EventRules;
import io.bromo.shared.contract.DataContract.FieldSpec;
import io.bromo.shared.contract.DataContract.MartSpec;
import io.bromo.shared.contract.DataContract.Measure;
import io.bromo.shared.contract.DataContract.MetricSpec;
import io.bromo.shared.contract.DataContract.PlainJsonIntake;
import io.bromo.shared.contract.DataContract.Retention;
import io.bromo.shared.contract.DataContract.Sample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads and validates config/data-contract.yml. All problems are reported together. */
public final class DataContractLoader {

  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
  private static final Pattern PATH = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
  private static final Set<String> RESERVED =
      Set.of(
          "event_id",
          "source",
          "type",
          "time",
          "ingested_at",
          "subject",
          "attrs",
          "request_id",
          "payload",
          "minute",
          "events",
          "subjects_state");
  private static final Set<String> FUNCTIONS = Set.of("sum", "min", "max", "p95");

  private final List<String> problems = new ArrayList<>();

  private DataContractLoader() {}

  /** Reads the contract file. */
  public static DataContract load(Path file) {
    try {
      return parse(Files.readString(file));
    } catch (IOException e) {
      throw new InvalidDataContractException(
          List.of("cannot read " + file + ": " + e.getMessage()));
    }
  }

  /** Parses contract YAML text. */
  public static DataContract parse(String yaml) {
    return new DataContractLoader().build(yaml);
  }

  /** Builds the contract and throws when any problem was found. */
  private DataContract build(String yaml) {
    Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    if (!(root instanceof Map<?, ?> map)) {
      throw new InvalidDataContractException(List.of("the file must contain a YAML mapping"));
    }
    Map<String, Object> top = asMap(map);
    int version = intValue(top, "version", "version", 1);
    if (version != 1) {
      problems.add("version: only version 1 is supported");
    }
    String database = text(top, "database", "database");
    if (database == null || !NAME.matcher(database).matches()) {
      problems.add("database: must match " + NAME.pattern());
    }
    Retention retention = retention(child(top, "retentionDays"));
    EventRules event = eventRules(child(top, "event"));
    List<FieldSpec> fields = fields(list(top, "fields"));
    MartSpec mart = mart(child(top, "mart"), fields);
    List<String> allowedBreakdowns = new ArrayList<>(List.of("type", "source"));
    allowedBreakdowns.addAll(mart.dimensions());
    List<MetricSpec> metrics = metrics(list(top, "metrics"), allowedBreakdowns);
    PlainJsonIntake plainJson = plainJson(child(child(top, "intake"), "plainJson"));
    Sample sample = sample(child(top, "sample"), event);
    if (!problems.isEmpty()) {
      throw new InvalidDataContractException(problems);
    }
    return new DataContract(
        version, database, retention, event, fields, mart, metrics, plainJson, sample);
  }

  /** Reads the retention block. */
  private Retention retention(Map<String, Object> map) {
    return new Retention(
        positive(map, "retentionDays.raw", "raw", 30),
        positive(map, "retentionDays.events", "events", 90),
        positive(map, "retentionDays.rejected", "rejected", 30),
        positive(map, "retentionDays.mart", "mart", 365));
  }

  /** Reads the CloudEvents envelope rules. */
  private EventRules eventRules(Map<String, Object> map) {
    String pattern = optionalText(map, "typePattern");
    Pattern compiled = Pattern.compile("^[a-z0-9][a-z0-9._-]*\\.v[0-9]+$");
    if (pattern != null) {
      try {
        compiled = Pattern.compile(pattern);
      } catch (PatternSyntaxException e) {
        problems.add("event.typePattern: not a valid regular expression: " + e.getDescription());
      }
    }
    return new EventRules(
        compiled,
        positive(map, "event.maxIdLength", "maxIdLength", 256),
        positive(map, "event.maxSourceLength", "maxSourceLength", 1024),
        positive(map, "event.futureToleranceMinutes", "futureToleranceMinutes", 5));
  }

  /** Reads the promoted fields. */
  private List<FieldSpec> fields(List<Object> items) {
    List<FieldSpec> fields = new ArrayList<>();
    Set<String> names = new LinkedHashSet<>();
    Set<String> paths = new LinkedHashSet<>();
    for (int i = 0; i < items.size(); i++) {
      String where = "fields[" + i + "]";
      Map<String, Object> item = asMap(items.get(i));
      String name = text(item, where + ".name", "name");
      String path = text(item, where + ".path", "path");
      String typeName = text(item, where + ".type", "type");
      FieldType type = typeName == null ? null : FieldType.parse(typeName).orElse(null);
      if (name == null || !NAME.matcher(name).matches()) {
        problems.add(where + ".name: must match " + NAME.pattern());
      } else if (RESERVED.contains(name)) {
        problems.add(where + ".name: '" + name + "' is reserved");
      } else if (!names.add(name)) {
        problems.add(where + ".name: duplicate name '" + name + "'");
      }
      if (path == null || !PATH.matcher(path).matches()) {
        problems.add(where + ".path: must be one key matching " + PATH.pattern());
      } else if (!paths.add(path)) {
        problems.add(where + ".path: duplicate path '" + path + "'");
      }
      if (type == null) {
        problems.add(where + ".type: use string, low_cardinality_string, float64, int64, or bool");
      }
      boolean nullable = Boolean.TRUE.equals(item.get("nullable"));
      String description = optionalText(item, "description");
      fields.add(new FieldSpec(name, path, type, nullable, description == null ? "" : description));
    }
    return fields;
  }

  /** Reads the mart and checks it against the fields. */
  private MartSpec mart(Map<String, Object> map, List<FieldSpec> fields) {
    Map<String, FieldSpec> byName = new LinkedHashMap<>();
    fields.forEach(f -> byName.put(f.name(), f));
    List<String> dimensions = new ArrayList<>();
    for (Object value : list(map, "dimensions")) {
      String name = String.valueOf(value);
      FieldSpec field = byName.get(name);
      if ("type".equals(name) || "source".equals(name)) {
        continue;
      }
      if (field == null || field.type() == null || !field.type().textual()) {
        problems.add(
            "mart.dimensions: '" + name + "' must be a string or low_cardinality_string field");
      } else if (field.nullable()) {
        problems.add("mart.dimensions: '" + name + "' must not be nullable");
      } else if (!dimensions.contains(name)) {
        dimensions.add(name);
      }
    }
    List<Measure> measures = new ArrayList<>();
    List<Object> items = list(map, "measures");
    for (int i = 0; i < items.size(); i++) {
      Map<String, Object> item = asMap(items.get(i));
      String field = text(item, "mart.measures[" + i + "].field", "field");
      FieldSpec spec = byName.get(field);
      if (spec == null || spec.type() == null || !spec.type().numeric()) {
        problems.add(
            "mart.measures[" + i + "].field: '" + field + "' must be a float64 or int64 field");
      }
      List<String> functions = new ArrayList<>();
      for (Object f : list(item, "functions")) {
        functions.add(String.valueOf(f));
      }
      if (functions.isEmpty() || !FUNCTIONS.containsAll(functions)) {
        problems.add(
            "mart.measures["
                + i
                + "].functions: use one or more of "
                + new java.util.TreeSet<>(FUNCTIONS));
      }
      measures.add(new Measure(field, functions));
    }
    return new MartSpec(dimensions, measures);
  }

  /** Reads the metric definitions. */
  private List<MetricSpec> metrics(List<Object> items, List<String> allowedBreakdowns) {
    List<MetricSpec> metrics = new ArrayList<>();
    Set<String> names = new LinkedHashSet<>();
    for (int i = 0; i < items.size(); i++) {
      String where = "metrics[" + i + "]";
      Map<String, Object> item = asMap(items.get(i));
      String name = text(item, where + ".name", "name");
      if (name == null || !NAME.matcher(name).matches()) {
        problems.add(where + ".name: must match " + NAME.pattern());
      } else if (!names.add(name)) {
        problems.add(where + ".name: duplicate name '" + name + "'");
      }
      String expression = text(item, where + ".expression", "expression");
      List<String> breakdowns = new ArrayList<>();
      for (Object b : list(item, "breakdowns")) {
        breakdowns.add(String.valueOf(b));
      }
      if (!allowedBreakdowns.containsAll(breakdowns)) {
        problems.add(where + ".breakdowns: allowed values are " + allowedBreakdowns);
      }
      String unit = optionalText(item, "unit");
      String description = optionalText(item, "description");
      metrics.add(
          new MetricSpec(
              name,
              unit == null ? "" : unit,
              description == null ? "" : description,
              expression,
              breakdowns));
    }
    return metrics;
  }

  /** Reads the optional plain JSON mapping. */
  private PlainJsonIntake plainJson(Map<String, Object> map) {
    if (map.isEmpty()) {
      return null;
    }
    Binding id = binding(map, "id", true);
    Binding time = binding(map, "time", true);
    Binding source = binding(map, "source", true);
    Binding type = binding(map, "type", true);
    Binding subject = binding(map, "subject", false);
    return new PlainJsonIntake(id, time, source, type, subject);
  }

  /** Reads one binding that has either a pointer or a constant. */
  private Binding binding(Map<String, Object> parent, String name, boolean required) {
    String where = "intake.plainJson." + name;
    Map<String, Object> map = child(parent, name);
    if (map.isEmpty()) {
      if (required) {
        problems.add(where + ": required");
      }
      return null;
    }
    String pointer = optionalText(map, "pointer");
    String constant = optionalText(map, "constant");
    if ((pointer == null) == (constant == null)) {
      problems.add(where + ": set exactly one of pointer or constant");
    } else if (pointer != null && !pointer.startsWith("/")) {
      problems.add(where + ".pointer: must be a JSON Pointer starting with /");
    }
    return new Binding(pointer, constant);
  }

  /** Reads the sample event and checks its type. */
  private Sample sample(Map<String, Object> map, EventRules event) {
    String type = text(map, "sample.type", "type");
    if (type == null || !event.typePattern().matcher(type).matches()) {
      problems.add("sample.type: must match event.typePattern");
    }
    String source = optionalText(map, "source");
    String subject = optionalText(map, "subject");
    return new Sample(type, source == null ? "sample-source" : source, subject, child(map, "data"));
  }

  /** Reads a required text value, recording a problem when it is absent. */
  private String text(Map<String, Object> map, String where, String key) {
    Object value = map.get(key);
    if (value == null) {
      problems.add(where + ": required");
      return null;
    }
    return String.valueOf(value);
  }

  /** Reads an optional text value. */
  private static String optionalText(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : String.valueOf(value);
  }

  /** Reads a positive integer with a default. */
  private int positive(Map<String, Object> map, String where, String key, int fallback) {
    Object value = map.get(key);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Integer i && i > 0) {
      return i;
    }
    problems.add(where + ": must be a positive integer");
    return fallback;
  }

  /** Reads an integer with a default. */
  private int intValue(Map<String, Object> map, String where, String key, int fallback) {
    Object value = map.get(key);
    if (value instanceof Integer i) {
      return i;
    }
    if (value != null) {
      problems.add(where + ": must be an integer");
    }
    return fallback;
  }

  /** Returns a child mapping, empty when absent. */
  private static Map<String, Object> child(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof Map<?, ?> m ? asMap(m) : Collections.emptyMap();
  }

  /** Returns a list, empty when absent. */
  private static List<Object> list(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
  }

  /** Casts a YAML mapping to string keys. */
  private static Map<String, Object> asMap(Object value) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> map) {
      map.forEach((k, v) -> result.put(String.valueOf(k), v));
    }
    return result;
  }
}
