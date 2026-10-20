package io.bromo.ingest;

import io.bromo.shared.ApiException;
import io.bromo.shared.FieldError;
import io.bromo.shared.contract.DataContract.Binding;
import io.bromo.shared.contract.DataContract.PlainJsonIntake;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads structured, binary, and batch CloudEvents HTTP requests into event JSON trees. */
public class CloudEventParser {

  private static final String STRUCTURED = "application/cloudevents+json";
  private static final String BATCH = "application/cloudevents-batch+json";
  private static final String JSON = "application/json";
  private static final String HEADER_PREFIX = "ce-";

  private final JsonMapper mapper;
  private final int maxBatchEvents;
  private final PlainJsonIntake plainJson;

  /**
   * Creates a parser with the JSON mapper, the maximum batch size, and the optional plain JSON
   * mapping.
   *
   * <p>CUSTOMIZE: to accept another input format, add a case in {@link #parse} that builds the same
   * CloudEvent shaped tree. The validator and the sink need no change.
   */
  public CloudEventParser(JsonMapper mapper, int maxBatchEvents, PlainJsonIntake plainJson) {
    this.mapper = mapper;
    this.maxBatchEvents = maxBatchEvents;
    this.plainJson = plainJson;
  }

  /** Parses a request body according to its content type. */
  public List<JsonNode> parse(String contentType, HttpHeaders headers, byte[] body) {
    String base =
        contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    return switch (base) {
      case STRUCTURED -> List.of(readJson(body));
      case BATCH -> readBatch(body);
      case JSON ->
          List.of(
              headers.getFirst(HEADER_PREFIX + "specversion") == null && plainJson != null
                  ? readPlain(body)
                  : readBinary(headers, body));
      default ->
          throw ApiException.unsupportedMediaType(
              "Use application/cloudevents+json, application/cloudevents-batch+json, or application/json with ce-* headers.");
    };
  }

  /** Reads a batch: a non empty JSON array within the configured size. */
  private List<JsonNode> readBatch(byte[] body) {
    JsonNode root = readJson(body);
    if (!root.isArray() || root.isEmpty()) {
      throw ApiException.invalidRequest("A batch must be a non empty JSON array.");
    }
    if (root.size() > maxBatchEvents) {
      throw ApiException.contentTooLarge("A batch holds at most " + maxBatchEvents + " events.");
    }
    List<JsonNode> events = new ArrayList<>(root.size());
    root.forEach(events::add);
    return events;
  }

  /** Builds an event from ce-* headers and a JSON body that becomes data. */
  private JsonNode readBinary(HttpHeaders headers, byte[] body) {
    if (headers.getFirst(HEADER_PREFIX + "specversion") == null) {
      throw ApiException.invalidEvent(
          "The request is not a CloudEvent.",
          List.of(
              new FieldError(
                  "/specversion",
                  "missing: use application/cloudevents+json or send ce-* headers")));
    }
    ObjectNode event = mapper.createObjectNode();
    headers.forEach(
        (name, values) -> {
          String lower = name.toLowerCase(Locale.ROOT);
          if (lower.startsWith(HEADER_PREFIX) && !values.isEmpty()) {
            event.put(lower.substring(HEADER_PREFIX.length()), values.get(0));
          }
        });
    event.put("datacontenttype", JSON);
    if (body.length > 0) {
      event.set("data", readJson(body));
    }
    return event;
  }

  /** Wraps a plain JSON body as a CloudEvent using the bindings from the data contract. */
  private JsonNode readPlain(byte[] body) {
    JsonNode root = readJson(body);
    if (!root.isObject()) {
      throw ApiException.invalidRequest("The request body must be a JSON object.");
    }
    ObjectNode event = mapper.createObjectNode();
    event.put("specversion", "1.0");
    bind(event, "id", plainJson.id(), root);
    bind(event, "source", plainJson.source(), root);
    bind(event, "type", plainJson.type(), root);
    bind(event, "time", plainJson.time(), root);
    bind(event, "subject", plainJson.subject(), root);
    event.put("datacontenttype", JSON);
    event.set("data", root);
    return event;
  }

  /**
   * Sets one attribute from a constant or a JSON Pointer, leaving it out when the pointer finds
   * nothing.
   */
  private static void bind(ObjectNode event, String name, Binding binding, JsonNode root) {
    if (binding == null) {
      return;
    }
    if (binding.constant() != null) {
      event.put(name, binding.constant());
      return;
    }
    JsonNode value = root.at(binding.pointer());
    if (!value.isMissingNode() && !value.isNull() && value.isValueNode()) {
      event.put(name, value.asString());
    }
  }

  /** Parses JSON bytes, mapping syntax errors to a 400. */
  private JsonNode readJson(byte[] body) {
    try {
      return mapper.readTree(body);
    } catch (JacksonException e) {
      throw ApiException.invalidRequest("The request body is not valid JSON.");
    }
  }
}
