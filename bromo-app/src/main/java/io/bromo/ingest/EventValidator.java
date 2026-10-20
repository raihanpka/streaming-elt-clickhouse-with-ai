package io.bromo.ingest;

import io.bromo.shared.FieldError;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Checks one CloudEvent against the ingest rules in docs/API.md. */
public class EventValidator {

  private static final Pattern TYPE = Pattern.compile("^[a-z0-9][a-z0-9._-]*\\.v[0-9]+$");
  private static final Duration MAX_FUTURE = Duration.ofMinutes(5);
  private static final int MAX_ID_LENGTH = 256;
  private static final int MAX_SOURCE_LENGTH = 1024;

  private final Clock clock;
  private final Duration retention;

  /** Creates a validator with the clock and the retention window in days. */
  public EventValidator(Clock clock, int retentionDays) {
    this.clock = clock;
    this.retention = Duration.ofDays(retentionDays);
  }

  /** Returns the field errors of an event, empty when valid. The prefix is "" or "/index". */
  public List<FieldError> validate(JsonNode event, String prefix) {
    List<FieldError> errors = new ArrayList<>();
    if (!event.isObject()) {
      errors.add(new FieldError(prefix, "The event must be a JSON object."));
      return errors;
    }
    checkSpecVersion(event, prefix, errors);
    checkText(event, "id", MAX_ID_LENGTH, prefix, errors);
    checkText(event, "source", MAX_SOURCE_LENGTH, prefix, errors);
    checkType(event, prefix, errors);
    checkTime(event, prefix, errors);
    checkDataContentType(event, prefix, errors);
    checkData(event, prefix, errors);
    return errors;
  }

  /** Requires specversion to be the string 1.0. */
  private static void checkSpecVersion(JsonNode event, String prefix, List<FieldError> errors) {
    JsonNode node = event.get("specversion");
    if (node == null || !node.isString() || !"1.0".equals(node.asString())) {
      errors.add(new FieldError(prefix + "/specversion", "must be the string \"1.0\""));
    }
  }

  /** Requires a non empty string up to a maximum length. */
  private static void checkText(
      JsonNode event, String name, int maxLength, String prefix, List<FieldError> errors) {
    JsonNode node = event.get(name);
    if (node == null || !node.isString() || node.asString().isEmpty()) {
      errors.add(new FieldError(prefix + "/" + name, "must be a non empty string"));
    } else if (node.asString().length() > maxLength) {
      errors.add(
          new FieldError(prefix + "/" + name, "must be at most " + maxLength + " characters"));
    }
  }

  /** Requires the type to match the naming pattern. */
  private static void checkType(JsonNode event, String prefix, List<FieldError> errors) {
    JsonNode node = event.get("type");
    if (node == null || !node.isString() || !TYPE.matcher(node.asString()).matches()) {
      errors.add(
          new FieldError(
              prefix + "/type",
              "must match " + TYPE.pattern() + ", for example com.example.payment.v1"));
    }
  }

  /** Requires an RFC 3339 time that is not too far ahead and not older than the retention. */
  private void checkTime(JsonNode event, String prefix, List<FieldError> errors) {
    JsonNode node = event.get("time");
    if (node == null || !node.isString()) {
      errors.add(new FieldError(prefix + "/time", "must be an RFC 3339 timestamp"));
      return;
    }
    Instant time;
    try {
      time = OffsetDateTime.parse(node.asString()).toInstant();
    } catch (DateTimeParseException e) {
      errors.add(new FieldError(prefix + "/time", "must be an RFC 3339 timestamp"));
      return;
    }
    Instant now = clock.instant();
    if (time.isAfter(now.plus(MAX_FUTURE))) {
      errors.add(new FieldError(prefix + "/time", "must not be more than 5 minutes in the future"));
    } else if (time.isBefore(now.minus(retention))) {
      errors.add(
          new FieldError(
              prefix + "/time",
              "must not be older than the retention of " + retention.toDays() + " days"));
    }
  }

  /** Requires application/json when a data content type is present. */
  private static void checkDataContentType(JsonNode event, String prefix, List<FieldError> errors) {
    JsonNode node = event.get("datacontenttype");
    if (node == null || node.isNull()) {
      return;
    }
    String base =
        node.isString() ? node.asString().split(";")[0].trim().toLowerCase(Locale.ROOT) : "";
    if (!"application/json".equals(base)) {
      errors.add(
          new FieldError(prefix + "/datacontenttype", "must be application/json when present"));
    }
  }

  /** Requires data to be a JSON object when present. */
  private static void checkData(JsonNode event, String prefix, List<FieldError> errors) {
    JsonNode node = event.get("data");
    if (node != null && !node.isNull() && !node.isObject()) {
      errors.add(new FieldError(prefix + "/data", "must be a JSON object when present"));
    }
  }
}
