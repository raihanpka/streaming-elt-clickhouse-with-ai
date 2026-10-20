package io.bromo.ingest;

import io.bromo.shared.ApiException;
import io.bromo.shared.FieldError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses, validates, and stores the events of one ingest request. */
@Service
public class IngestService {

  private static final String BATCH = "application/cloudevents-batch+json";

  private final CloudEventParser parser;
  private final EventValidator validator;
  private final RawEventWriter writer;
  private final JsonMapper mapper;
  private final IngestProperties properties;

  /** Creates the service from its collaborators. */
  public IngestService(
      CloudEventParser parser,
      EventValidator validator,
      RawEventWriter writer,
      JsonMapper mapper,
      IngestProperties properties) {
    this.parser = parser;
    this.validator = validator;
    this.writer = writer;
    this.mapper = mapper;
    this.properties = properties;
  }

  /** Stores the events of a request and returns how many were accepted. */
  public int ingest(String contentType, HttpHeaders headers, InputStream body) throws IOException {
    boolean batch = isBatch(contentType);
    byte[] bytes = readBounded(body, batch);
    List<JsonNode> events = parser.parse(contentType, headers, bytes);
    List<String> payloads = new ArrayList<>(events.size());
    List<FieldError> errors = new ArrayList<>();
    for (int i = 0; i < events.size(); i++) {
      String prefix = batch ? "/" + i : "";
      errors.addAll(validator.validate(events.get(i), prefix));
      String payload = mapper.writeValueAsString(events.get(i));
      if (payload.getBytes(StandardCharsets.UTF_8).length > properties.maxEventBytes()) {
        throw ApiException.contentTooLarge(
            "An event is larger than " + properties.maxEventBytes() + " bytes.");
      }
      payloads.add(payload);
    }
    if (!errors.isEmpty()) {
      throw ApiException.invalidEvent("The request contains invalid events.", errors);
    }
    writer.write(UUID.randomUUID(), payloads);
    return payloads.size();
  }

  /** Reads the body up to the limit for the mode and fails with 413 above it. */
  private byte[] readBounded(InputStream body, boolean batch) throws IOException {
    long limit =
        batch
            ? (long) properties.maxEventBytes() * properties.maxBatchEvents()
            : properties.maxEventBytes();
    byte[] bytes = body.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
    if (bytes.length > limit) {
      throw ApiException.contentTooLarge("The request body is larger than " + limit + " bytes.");
    }
    return bytes;
  }

  /** Tells whether the content type is the batch mode. */
  private static boolean isBatch(String contentType) {
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith(BATCH);
  }
}
