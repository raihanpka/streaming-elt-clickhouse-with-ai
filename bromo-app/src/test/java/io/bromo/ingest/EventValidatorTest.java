package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bromo.shared.FieldError;
import io.bromo.shared.contract.DataContract;
import io.bromo.shared.contract.DataContract.FieldSpec;
import io.bromo.shared.contract.DataContractLoader;
import io.bromo.testsupport.TestContract;
import io.bromo.testsupport.TestEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validator tests build their events from the contract sample, so they follow the user's data. */
class EventValidatorTest {

  static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
  final JsonMapper mapper = JsonMapper.builder().build();
  final DataContract contract = TestContract.load();
  final EventValidator validator = new EventValidator(Clock.fixed(NOW, ZoneOffset.UTC), contract);

  ObjectNode valid() {
    return TestEvents.tree(contract, "e1", NOW.minusSeconds(60));
  }

  List<FieldError> validate(JsonNode event) {
    return validator.validate(event, "");
  }

  String firstPointer(JsonNode event) {
    return validate(event).get(0).pointer();
  }

  @Test
  void validEventHasNoErrors() {
    assertTrue(validate(valid()).isEmpty());
  }

  @Test
  void eventWithoutDataIsValid() {
    ObjectNode event = valid();
    event.remove("data");
    assertTrue(validate(event).isEmpty());
  }

  @Test
  void nonObjectIsRejected() {
    assertEquals("", validator.validate(mapper.readTree("[1]"), "").get(0).pointer());
  }

  @Test
  void missingAttributesAreReportedWithPointers() {
    List<String> pointers =
        validate(mapper.createObjectNode()).stream().map(FieldError::pointer).toList();
    assertEquals(List.of("/specversion", "/id", "/source", "/type", "/time"), pointers);
  }

  @Test
  void specversionMustBeOnePointZero() {
    assertEquals("/specversion", firstPointer(valid().put("specversion", "0.3")));
  }

  @Test
  void idAndSourceFollowTheContractLengthLimits() {
    assertEquals(
        "/id", firstPointer(valid().put("id", "x".repeat(contract.event().maxIdLength() + 1))));
    assertEquals(
        "/source",
        firstPointer(valid().put("source", "x".repeat(contract.event().maxSourceLength() + 1))));
  }

  @Test
  void emptyTypeIsRejected() {
    assertEquals("/type", firstPointer(valid().put("type", "")));
  }

  @Test
  void timeMustBeRfc3339() {
    assertEquals("/time", firstPointer(valid().put("time", "yesterday")));
  }

  @Test
  void timeFollowsTheFutureToleranceInTheContract() {
    Duration tolerance = Duration.ofMinutes(contract.event().futureToleranceMinutes());
    assertEquals(
        "/time", firstPointer(valid().put("time", NOW.plus(tolerance).plusSeconds(60).toString())));
    assertTrue(
        validate(valid().put("time", NOW.plus(tolerance).minusSeconds(60).toString())).isEmpty());
  }

  @Test
  void timeFollowsTheEventsRetentionInTheContract() {
    Duration retention = Duration.ofDays(contract.retentionDays().events());
    assertEquals(
        "/time",
        firstPointer(valid().put("time", NOW.minus(retention).minusSeconds(3600).toString())));
    assertTrue(
        validate(valid().put("time", NOW.minus(retention).plusSeconds(3600).toString())).isEmpty());
  }

  @Test
  void dataMustBeAnObject() {
    ObjectNode event = valid();
    event.put("data", 5);
    assertEquals("/data", firstPointer(event));
  }

  @Test
  void datacontenttypeMustBeJson() {
    assertEquals("/datacontenttype", firstPointer(valid().put("datacontenttype", "text/xml")));
    assertTrue(validate(valid().put("datacontenttype", "application/json")).isEmpty());
  }

  @Test
  void everyPromotedFieldMustHaveItsDeclaredType() {
    for (FieldSpec field : contract.fields()) {
      ObjectNode event = valid();
      ObjectNode data = (ObjectNode) event.get("data");
      if (field.type().textual()) {
        data.put(field.path(), 5);
      } else {
        data.put(field.path(), "not-a-value");
      }
      assertEquals("/data/" + field.path(), firstPointer(event), field.name());
    }
  }

  @Test
  void nullPromotedFieldsAreAllowed() {
    for (FieldSpec field : contract.fields()) {
      ObjectNode event = valid();
      ((ObjectNode) event.get("data")).putNull(field.path());
      assertTrue(validate(event).isEmpty(), field.name());
    }
  }

  @Test
  void typePatternComesFromTheContract() {
    DataContract strict =
        DataContractLoader.parse(
            TestContract.yaml()
                .replace(
                    "typePattern: '^[a-z0-9][a-z0-9._-]*\\.v[0-9]+$'",
                    "typePattern: '^only\\.this\\.v1$'")
                .replace("type: " + contract.sample().type(), "type: only.this.v1"));
    EventValidator v = new EventValidator(Clock.fixed(NOW, ZoneOffset.UTC), strict);
    assertEquals("/type", v.validate(valid(), "").get(0).pointer());
    assertTrue(v.validate(valid().put("type", "only.this.v1"), "").isEmpty());
  }

  @Test
  void pointerPrefixIsUsedForBatchItems() {
    assertEquals(
        "/3/id",
        validator.validate(mapper.readTree("{\"specversion\":\"1.0\"}"), "/3").get(0).pointer());
  }
}
