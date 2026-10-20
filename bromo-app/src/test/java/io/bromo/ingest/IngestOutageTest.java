package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.bromo.testsupport.ClickHouseFixture;
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

@SpringBootTest(
    classes = io.bromo.bootstrap.BromoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "bromo.ingest.api-keys=it-key",
      "management.server.port=",
      "bromo.clickhouse.timeout-seconds=2"
    })
class IngestOutageTest {

  static final ClickHouseFixture clickhouse = new ClickHouseFixture();

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

  @Test
  void stoppedClickHouseReturnsServiceUnavailableWithRetryAfter() throws Exception {
    clickhouse.stop();
    String event = TestEvents.recent("out-1");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
            .header("Content-Type", "application/cloudevents+json")
            .header("Authorization", "Bearer it-key")
            .POST(HttpRequest.BodyPublishers.ofString(event))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(503, response.statusCode());
    assertEquals("5", response.headers().firstValue("Retry-After").orElse(""));
    assertTrue(response.body().contains("urn:bromo:problem:unavailable"));
  }
}
