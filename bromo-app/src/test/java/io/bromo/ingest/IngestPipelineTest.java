package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.bromo.testsupport.ClickHouseFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

  HttpResponse<String> post(String contentType, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
            .header("Content-Type", contentType)
            .header("Authorization", "Bearer it-key")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  static String event(String id, double amount) {
    return "{\"specversion\":\"1.0\",\"id\":\""
        + id
        + "\",\"source\":\"it\",\"type\":\"com.example.payment.v1\","
        + "\"time\":\""
        + Instant.now().minusSeconds(10)
        + "\",\"data\":{\"amount\":"
        + amount
        + ",\"label\":\"ok\"}}";
  }

  @Test
  void structuredEventIsQueryableRightAfterTheResponse() throws Exception {
    HttpResponse<String> response = post("application/cloudevents+json", event("struct-1", 42.5));
    assertEquals(200, response.statusCode());
    assertEquals(
        "1", clickhouse.query("SELECT count() FROM bromo.events WHERE event_id = 'struct-1'"));
    assertEquals(
        "42.5", clickhouse.query("SELECT amount FROM bromo.events WHERE event_id = 'struct-1'"));
  }

  @Test
  void batchStoresEveryEvent() throws Exception {
    String body =
        "[" + event("batch-1", 1) + "," + event("batch-2", 2) + "," + event("batch-3", 3) + "]";
    assertEquals(200, post("application/cloudevents-batch+json", body).statusCode());
    assertEquals(
        "3", clickhouse.query("SELECT count() FROM bromo.events WHERE event_id LIKE 'batch-%'"));
  }

  @Test
  void retriedBatchLeavesOneRowPerEventAfterMerge() throws Exception {
    String body = "[" + event("retry-1", 1) + "," + event("retry-2", 2) + "]";
    assertEquals(200, post("application/cloudevents-batch+json", body).statusCode());
    assertEquals(200, post("application/cloudevents-batch+json", body).statusCode());
    clickhouse.query("OPTIMIZE TABLE bromo.events FINAL");
    assertEquals(
        "2", clickhouse.query("SELECT count() FROM bromo.events WHERE event_id LIKE 'retry-%'"));
  }
}
