package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bromo.testsupport.ClickHouseFixture;
import io.bromo.testsupport.TestEvents;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Measures how long an event takes to become queryable. Writes build/reports/ingest-latency.txt.
 */
@SpringBootTest(
    classes = io.bromo.bootstrap.BromoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"bromo.ingest.api-keys=it-key", "management.server.port="})
class IngestLatencyTest {

  static final ClickHouseFixture clickhouse = new ClickHouseFixture();
  static final int SINGLE_REQUESTS = 200;
  static final int BATCHES = 10;
  static final int BATCH_SIZE = 500;

  @AfterAll
  static void cleanUp() {
    clickhouse.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("bromo.clickhouse.url", clickhouse::url);
    registry.add("bromo.ingest.clickhouse-password", () -> ClickHouseFixture.INGEST_PASSWORD);
  }

  @Value("${local.server.port}")
  int port;

  final HttpClient http = HttpClient.newHttpClient();

  HttpResponse<String> post(String contentType, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
            .header("Content-Type", contentType)
            .header("Authorization", "Bearer it-key")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  static String event(String id) {
    return TestEvents.json(id, Instant.now().minusSeconds(5));
  }

  static double percentile(List<Double> sorted, double p) {
    return sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(p * sorted.size()) - 1));
  }

  @Test
  void eventsAreQueryableRightAfterTheResponse() throws Exception {
    post("application/cloudevents+json", event("warmup"));
    List<Double> single = new ArrayList<>();
    for (int i = 0; i < SINGLE_REQUESTS; i++) {
      long start = System.nanoTime();
      assertEquals(200, post("application/cloudevents+json", event("single-" + i)).statusCode());
      assertEquals(
          "1",
          clickhouse.query(
              "SELECT count() FROM "
                  + clickhouse.silverTable()
                  + " WHERE event_id = 'single-"
                  + i
                  + "'"));
      single.add((System.nanoTime() - start) / 1e6);
    }
    List<Double> batches = new ArrayList<>();
    long batchStart = System.nanoTime();
    for (int b = 0; b < BATCHES; b++) {
      StringBuilder body = new StringBuilder("[");
      for (int i = 0; i < BATCH_SIZE; i++) {
        body.append(i == 0 ? "" : ",").append(event("batch-" + b + "-" + i));
      }
      body.append("]");
      long start = System.nanoTime();
      assertEquals(200, post("application/cloudevents-batch+json", body.toString()).statusCode());
      assertEquals(
          String.valueOf(BATCH_SIZE),
          clickhouse.query(
              "SELECT count() FROM "
                  + clickhouse.silverTable()
                  + " WHERE event_id LIKE 'batch-"
                  + b
                  + "-%'"));
      batches.add((System.nanoTime() - start) / 1e6);
    }
    double batchSeconds = (System.nanoTime() - batchStart) / 1e9;

    Collections.sort(single);
    Collections.sort(batches);
    String report =
        String.format(
            "single event, request until row queryable (%d requests): p50=%.1f ms p95=%.1f ms max=%.1f ms%n"
                + "batch of %d events, request until rows queryable (%d batches): p50=%.1f ms p95=%.1f ms%n"
                + "batch throughput including the verification query: %.0f events/s%n",
            SINGLE_REQUESTS,
            percentile(single, 0.5),
            percentile(single, 0.95),
            single.get(single.size() - 1),
            BATCH_SIZE,
            BATCHES,
            percentile(batches, 0.5),
            percentile(batches, 0.95),
            (BATCHES * BATCH_SIZE) / batchSeconds);
    Path out = Path.of("build/reports/ingest-latency.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report);
    System.out.println(report);
    assertTrue(percentile(single, 0.95) < 5000, "p95 over 5 seconds: " + report);
  }
}
