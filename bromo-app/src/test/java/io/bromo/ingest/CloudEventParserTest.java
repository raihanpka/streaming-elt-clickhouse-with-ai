package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.bromo.shared.ApiException;
import io.bromo.shared.contract.DataContract;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CloudEventParserTest {

  final JsonMapper mapper = JsonMapper.builder().build();
  final CloudEventParser parser = new CloudEventParser(mapper, 3, null);

  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void structuredModeReturnsOneEvent() {
    List<JsonNode> events =
        parser.parse(
            "application/cloudevents+json; charset=utf-8",
            new HttpHeaders(),
            bytes("{\"id\":\"e1\"}"));
    assertEquals(1, events.size());
    assertEquals("e1", events.get(0).get("id").asString());
  }

  @Test
  void batchModeReturnsEveryEvent() {
    List<JsonNode> events =
        parser.parse(
            "application/cloudevents-batch+json",
            new HttpHeaders(),
            bytes("[{\"id\":\"a\"},{\"id\":\"b\"}]"));
    assertEquals(2, events.size());
  }

  @Test
  void batchAboveTheLimitIsTooLarge() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                parser.parse(
                    "application/cloudevents-batch+json",
                    new HttpHeaders(),
                    bytes("[{},{},{},{}]")));
    assertEquals(HttpStatus.CONTENT_TOO_LARGE, ex.status());
  }

  @Test
  void emptyBatchIsInvalid() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                parser.parse("application/cloudevents-batch+json", new HttpHeaders(), bytes("[]")));
    assertEquals(HttpStatus.BAD_REQUEST, ex.status());
  }

  @Test
  void batchThatIsNotAnArrayIsInvalid() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                parser.parse("application/cloudevents-batch+json", new HttpHeaders(), bytes("{}")));
    assertEquals(HttpStatus.BAD_REQUEST, ex.status());
  }

  @Test
  void binaryModeBuildsTheEventFromHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("ce-specversion", "1.0");
    headers.add("ce-id", "b1");
    headers.add("ce-source", "src");
    headers.add("ce-type", "a.v1");
    headers.add("ce-time", "2026-10-20T09:59:00Z");
    headers.add("ce-subject", "card-1");
    List<JsonNode> events = parser.parse("application/json", headers, bytes("{\"amount\":2}"));
    JsonNode event = events.get(0);
    assertEquals("b1", event.get("id").asString());
    assertEquals("card-1", event.get("subject").asString());
    assertEquals("application/json", event.get("datacontenttype").asString());
    assertEquals(2, event.get("data").get("amount").asInt());
  }

  @Test
  void jsonWithoutCloudEventHeadersIsInvalid() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> parser.parse("application/json", new HttpHeaders(), bytes("{}")));
    assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    assertEquals("/specversion", ex.errors().get(0).pointer());
  }

  @Test
  void malformedJsonIsInvalid() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> parser.parse("application/cloudevents+json", new HttpHeaders(), bytes("{oops")));
    assertEquals(HttpStatus.BAD_REQUEST, ex.status());
  }

  @Test
  void otherContentTypesAreUnsupported() {
    ApiException ex =
        assertThrows(
            ApiException.class, () -> parser.parse("text/plain", new HttpHeaders(), bytes("x")));
    assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.status());
    assertThrows(ApiException.class, () -> parser.parse(null, new HttpHeaders(), bytes("x")));
  }

  @Test
  void plainJsonIsMappedWithTheContractBindings() {
    DataContract.PlainJsonIntake intake =
        new DataContract.PlainJsonIntake(
            new DataContract.Binding("/orderId", null),
            new DataContract.Binding("/createdAt", null),
            new DataContract.Binding(null, "shop-backend"),
            new DataContract.Binding(null, "com.shop.order.v1"),
            new DataContract.Binding("/customer/id", null));
    CloudEventParser plain = new CloudEventParser(mapper, 3, intake);
    JsonNode event =
        plain
            .parse(
                "application/json",
                new HttpHeaders(),
                bytes(
                    "{\"orderId\":1234,\"createdAt\":\"2026-10-20T09:59:00Z\",\"customer\":{\"id\":\"c9\"},\"total\":5}"))
            .get(0);
    assertEquals("1234", event.get("id").asString());
    assertEquals("shop-backend", event.get("source").asString());
    assertEquals("com.shop.order.v1", event.get("type").asString());
    assertEquals("c9", event.get("subject").asString());
    assertEquals("2026-10-20T09:59:00Z", event.get("time").asString());
    assertEquals(5, event.get("data").get("total").asInt());
    assertEquals("1.0", event.get("specversion").asString());
  }

  @Test
  void plainJsonWithAMissingPointerLeavesTheAttributeOutForTheValidator() {
    DataContract.PlainJsonIntake intake =
        new DataContract.PlainJsonIntake(
            new DataContract.Binding("/orderId", null),
            new DataContract.Binding("/createdAt", null),
            new DataContract.Binding(null, "s"),
            new DataContract.Binding(null, "a.v1"),
            null);
    JsonNode event =
        new CloudEventParser(mapper, 3, intake)
            .parse("application/json", new HttpHeaders(), bytes("{\"createdAt\":\"t\"}"))
            .get(0);
    assertEquals(false, event.has("id"));
  }
}
