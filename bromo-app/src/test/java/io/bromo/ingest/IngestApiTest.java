package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = io.bromo.bootstrap.BromoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"bromo.ingest.api-keys=test-key", "management.server.port="})
class IngestApiTest {

  @Value("${local.server.port}")
  int port;

  @MockitoBean RawEventWriter writer;

  HttpResponse<String> post(String authorization, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
            .header("Content-Type", "application/cloudevents+json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  String event() {
    return "{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"s\",\"type\":\"a.v1\",\"time\":\""
        + Instant.now().minusSeconds(30)
        + "\"}";
  }

  @Test
  void missingKeyIsUnauthorizedWithChallenge() throws Exception {
    HttpResponse<String> response = post(null, event());
    assertEquals(401, response.statusCode());
    assertEquals(
        "Bearer realm=\"bromo\"", response.headers().firstValue("WWW-Authenticate").orElse(""));
    assertTrue(
        response
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("application/problem+json"));
    assertTrue(response.body().contains("urn:bromo:problem:unauthenticated"));
  }

  @Test
  void wrongKeyIsUnauthorized() throws Exception {
    assertEquals(401, post("Bearer wrong", event()).statusCode());
  }

  @Test
  void validKeyStoresTheEvent() throws Exception {
    HttpResponse<String> response = post("Bearer test-key", event());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"accepted\":1"));
    verify(writer).write(any(), anyList());
  }
}
