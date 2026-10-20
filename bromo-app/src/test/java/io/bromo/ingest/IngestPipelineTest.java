package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.bromo.shared.contract.DataContract;
import io.bromo.shared.contract.DataContract.FieldSpec;
import io.bromo.testsupport.ClickHouseFixture;
import io.bromo.testsupport.TestContract;
import io.bromo.testsupport.TestEvents;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the whole ingest path against a real ClickHouse built from the data contract. */
@SpringBootTest(
    classes = io.bromo.bootstrap.BromoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"bromo.ingest.api-keys=it-key", "management.server.port="})
class IngestPipelineTest {

  static final ClickHouseFixture clickhouse = new ClickHouseFixture();

  @AfterAll
  static void stopClickHouse() {
    clickhouse.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("bromo.clickhouse.url", clickhouse::url);
    registry.add("bromo.ingest.clickhouse-password", () -> ClickHouseFixture.INGEST_PASSWORD);
  }

  @Value("${local.server.port}")
  int port;

  final DataContract contract = TestContract.load();

  HttpResponse<String> post(String contentType, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
            .header("Content-Type", contentType)
            .header("Authorization", "Bearer it-key")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  static String display(Object value) {
    if (value instanceof Double d && d == Math.rint(d)) {
      return String.valueOf(d.longValue());
    }
    return String.valueOf(value);
  }

  @Test
  void structuredEventIsQueryableInSilverRightAfterTheResponse() throws Exception {
    assertEquals(
        200, post("application/cloudevents+json", TestEvents.recent("struct-1")).statusCode());
    assertEquals(
        "1",
        clickhouse.query(
            "SELECT count() FROM " + clickhouse.silverTable() + " WHERE event_id = 'struct-1'"));
  }

  @Test
  void everyPromotedFieldOfTheSampleArrivesTyped() throws Exception {
    assertEquals(
        200, post("application/cloudevents+json", TestEvents.recent("typed-1")).statusCode());
    for (FieldSpec field : contract.fields()) {
      Object expected = contract.sample().data().get(field.path());
      if (expected != null) {
        assertEquals(
            display(expected),
            clickhouse.query(
                "SELECT `"
                    + field.name()
                    + "` FROM "
                    + clickhouse.silverTable()
                    + " WHERE event_id = 'typed-1'"),
            field.name());
      }
    }
  }

  @Test
  void batchStoresEveryEventAndReachesGold() throws Exception {
    String body =
        "["
            + TestEvents.recent("batch-1")
            + ","
            + TestEvents.recent("batch-2")
            + ","
            + TestEvents.recent("batch-3")
            + "]";
    assertEquals(200, post("application/cloudevents-batch+json", body).statusCode());
    assertEquals(
        "3",
        clickhouse.query(
            "SELECT count() FROM " + clickhouse.silverTable() + " WHERE event_id LIKE 'batch-%'"));
    assertEquals(
        "1", clickhouse.query("SELECT toUInt8(sum(events) >= 3) FROM " + clickhouse.goldTable()));
  }

  @Test
  void retriedBatchLeavesOneSilverRowPerEventAfterMerge() throws Exception {
    String body = "[" + TestEvents.recent("retry-1") + "," + TestEvents.recent("retry-2") + "]";
    assertEquals(200, post("application/cloudevents-batch+json", body).statusCode());
    assertEquals(200, post("application/cloudevents-batch+json", body).statusCode());
    clickhouse.query("OPTIMIZE TABLE " + clickhouse.silverTable() + " FINAL");
    assertEquals(
        "2",
        clickhouse.query(
            "SELECT count() FROM " + clickhouse.silverTable() + " WHERE event_id LIKE 'retry-%'"));
  }
}
