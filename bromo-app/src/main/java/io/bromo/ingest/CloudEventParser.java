package io.bromo.ingest;

import io.bromo.shared.ApiException;
import io.bromo.shared.FieldError;
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

  /** Creates a parser with the JSON mapper and the maximum batch size. */
  public CloudEventParser(JsonMapper mapper, int maxBatchEvents) {
    this.mapper = mapper;
    this.maxBatchEvents = maxBatchEvents;
  }

  /** Parses a request body according to its content type. */
  public List<JsonNode> parse(String contentType, HttpHeaders headers, byte[] body) {
    String base =
        contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    return switch (base) {
      case STRUCTURED -> List.of(readJson(body));
      case BATCH -> readBatch(body);
      case JSON -> List.of(readBinary(headers, body));
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

  /** Parses JSON bytes, mapping syntax errors to a 400. */
  private JsonNode readJson(byte[] body) {
    try {
      return mapper.readTree(body);
    } catch (JacksonException e) {
      throw ApiException.invalidRequest("The request body is not valid JSON.");
    }
  }
}
