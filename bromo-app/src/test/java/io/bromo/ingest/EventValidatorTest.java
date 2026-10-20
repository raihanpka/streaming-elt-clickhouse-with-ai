package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bromo.shared.FieldError;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EventValidatorTest {

  static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
  final JsonMapper mapper = JsonMapper.builder().build();
  final EventValidator validator = new EventValidator(Clock.fixed(NOW, ZoneOffset.UTC), 90);

  JsonNode json(String text) {
    return mapper.readTree(text);
  }

  List<FieldError> validate(String text) {
    return validator.validate(json(text), "");
  }

  static final String VALID =
      "{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"s\",\"type\":\"com.example.payment.v1\","
          + "\"time\":\"2026-10-20T09:59:00Z\",\"data\":{\"amount\":1}}";

  @Test
  void validEventHasNoErrors() {
    assertTrue(validate(VALID).isEmpty());
  }

  @Test
  void minimalEventWithoutDataIsValid() {
    assertTrue(
        validate(
                "{\"specversion\":\"1.0\",\"id\":\"e\",\"source\":\"s\",\"type\":\"a.v1\",\"time\":\"2026-10-20T09:59:00Z\"}")
            .isEmpty());
  }

  @Test
  void nonObjectIsRejected() {
    assertEquals("", validator.validate(json("[1]"), "").get(0).pointer());
  }

  @Test
  void missingAttributesAreReportedWithPointers() {
    List<String> pointers = validate("{}").stream().map(FieldError::pointer).toList();
    assertEquals(List.of("/specversion", "/id", "/source", "/type", "/time"), pointers);
  }

  @Test
  void specversionMustBeOnePointZero() {
    assertEquals("/specversion", validate(VALID.replace("\"1.0\"", "\"0.3\"")).get(0).pointer());
  }

  @Test
  void idAndSourceHaveLengthLimits() {
    String longId = "x".repeat(257);
    assertEquals("/id", validate(VALID.replace("\"e1\"", "\"" + longId + "\"")).get(0).pointer());
    String longSource = "x".repeat(1025);
    assertEquals(
        "/source",
        validate(VALID.replace("\"source\":\"s\"", "\"source\":\"" + longSource + "\""))
            .get(0)
            .pointer());
  }

  @Test
  void typeMustMatchTheNamingPattern() {
    assertEquals(
        "/type", validate(VALID.replace("com.example.payment.v1", "Payment")).get(0).pointer());
    assertEquals(
        "/type", validate(VALID.replace("com.example.payment.v1", "payment")).get(0).pointer());
  }

  @Test
  void timeMustBeRfc3339() {
    assertEquals(
        "/time", validate(VALID.replace("2026-10-20T09:59:00Z", "yesterday")).get(0).pointer());
  }

  @Test
  void timeCannotBeMoreThanFiveMinutesAhead() {
    assertEquals(
        "/time",
        validate(VALID.replace("2026-10-20T09:59:00Z", "2026-10-20T10:06:00Z")).get(0).pointer());
    assertTrue(validate(VALID.replace("2026-10-20T09:59:00Z", "2026-10-20T10:04:00Z")).isEmpty());
  }

  @Test
  void timeCannotBeOlderThanRetention() {
    assertEquals(
        "/time",
        validate(VALID.replace("2026-10-20T09:59:00Z", "2026-07-01T00:00:00Z")).get(0).pointer());
    assertTrue(validate(VALID.replace("2026-10-20T09:59:00Z", "2026-08-01T00:00:00Z")).isEmpty());
  }

  @Test
  void dataMustBeAnObject() {
    assertEquals("/data", validate(VALID.replace("{\"amount\":1}", "5")).get(0).pointer());
  }

  @Test
  void datacontenttypeMustBeJson() {
    String xml = VALID.replace("\"data\"", "\"datacontenttype\":\"text/xml\",\"data\"");
    assertEquals("/datacontenttype", validate(xml).get(0).pointer());
    String json = VALID.replace("\"data\"", "\"datacontenttype\":\"application/json\",\"data\"");
    assertTrue(validate(json).isEmpty());
  }

  @Test
  void pointerPrefixIsUsedForBatchItems() {
    assertEquals(
        "/3/id", validator.validate(json("{\"specversion\":\"1.0\"}"), "/3").get(0).pointer());
  }
}
