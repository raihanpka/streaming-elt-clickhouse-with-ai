package io.bromo.ingest;

import io.bromo.shared.FieldError;
import io.bromo.shared.contract.DataContract;
import io.bromo.shared.contract.DataContract.FieldSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Checks one CloudEvent against the rules in the data contract.
 *
 * <p>CUSTOMIZE: the envelope rules and the typed fields come from config/data-contract.yml. Edit
 * that file, not this class. To add a new kind of check, add a method here and call it from
 * validate.
 */
public class EventValidator {

  private final Clock clock;
  private final DataContract contract;
  private final Duration retention;
  private final Duration futureTolerance;

  /** Creates a validator from the clock and the data contract. */
  public EventValidator(Clock clock, DataContract contract) {
    this.clock = clock;
    this.contract = contract;
    this.retention = Duration.ofDays(contract.retentionDays().silver());
    this.futureTolerance = Duration.ofMinutes(contract.event().futureToleranceMinutes());
  }

  /** Returns the field errors of an event, empty when valid. The prefix is "" or "/index". */
  public List<FieldError> validate(JsonNode event, String prefix) {
    List<FieldError> errors = new ArrayList<>();
    if (!event.isObject()) {
      errors.add(new FieldError(prefix, "The event must be a JSON object."));
      return errors;
    }
    checkSpecVersion(event, prefix, errors);
    checkText(event, "id", contract.event().maxIdLength(), prefix, errors);
    checkText(event, "source", contract.event().maxSourceLength(), prefix, errors);
    checkType(event, prefix, errors);
    checkTime(event, prefix, errors);
    checkDataContentType(event, prefix, errors);
    checkData(event, prefix, errors);
    checkFields(event, prefix, errors);
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
  private void checkType(JsonNode event, String prefix, List<FieldError> errors) {
    JsonNode node = event.get("type");
    if (node == null
        || !node.isString()
        || !contract.event().typePattern().matcher(node.asString()).matches()) {
      errors.add(
          new FieldError(
              prefix + "/type", "must match " + contract.event().typePattern().pattern()));
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
    if (time.isAfter(now.plus(futureTolerance))) {
      errors.add(
          new FieldError(
              prefix + "/time",
              "must not be more than " + futureTolerance.toMinutes() + " minutes in the future"));
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

  /** Checks that promoted fields present in data have the declared type. */
  private void checkFields(JsonNode event, String prefix, List<FieldError> errors) {
    JsonNode data = event.get("data");
    if (data == null || !data.isObject()) {
      return;
    }
    for (FieldSpec field : contract.fields()) {
      JsonNode value = data.get(field.path());
      if (value == null || value.isNull()) {
        continue;
      }
      boolean ok =
          switch (field.type()) {
            case STRING, LOW_CARDINALITY_STRING -> value.isString();
            case FLOAT64 -> value.isNumber();
            case INT64 -> value.isIntegralNumber();
            case BOOL -> value.isBoolean();
          };
      if (!ok) {
        errors.add(
            new FieldError(
                prefix + "/data/" + field.path(),
                "must be of type " + field.type().name().toLowerCase(Locale.ROOT)));
      }
    }
  }
}
