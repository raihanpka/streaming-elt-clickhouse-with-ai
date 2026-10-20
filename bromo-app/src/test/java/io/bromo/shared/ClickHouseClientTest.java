package io.bromo.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ClickHouseClientTest {

  HttpServer server;
  final AtomicReference<String> query = new AtomicReference<>();
  final AtomicReference<String> body = new AtomicReference<>();
  final AtomicReference<String> user = new AtomicReference<>();
  final AtomicReference<String> key = new AtomicReference<>();
  volatile int status = 200;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          query.set(
              URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          user.set(exchange.getRequestHeaders().getFirst("X-ClickHouse-User"));
          key.set(exchange.getRequestHeaders().getFirst("X-ClickHouse-Key"));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  ClickHouseClient client() {
    return new ClickHouseClient(
        new ClickHouseProperties("http://127.0.0.1:" + server.getAddress().getPort(), 2));
  }

  @Test
  void sendsAsyncInsertWithCredentialsAndBody() {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("async_insert", "1");
    settings.put("wait_for_async_insert", "1");
    client()
        .insertJsonEachRow(
            "bromo.raw", "request_id, payload", "{\"a\":1}\n", "ingest_writer", "pw", settings);

    assertTrue(
        query.get().contains("INSERT INTO bromo.raw (request_id, payload) FORMAT JSONEachRow"));
    assertTrue(query.get().contains("async_insert=1"));
    assertTrue(query.get().contains("wait_for_async_insert=1"));
    assertEquals("{\"a\":1}\n", body.get());
    assertEquals("ingest_writer", user.get());
    assertEquals("pw", key.get());
  }

  @Test
  void connectionFailureIsUnavailable() {
    server.stop(0);
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> client().insertJsonEachRow("bromo.raw", "payload", "{}\n", "u", "p", Map.of()));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
    assertEquals(5, ex.retryAfterSeconds());
  }

  @Test
  void gatewayStatusIsUnavailable() {
    status = 503;
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> client().insertJsonEachRow("bromo.raw", "payload", "{}\n", "u", "p", Map.of()));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
  }

  @Test
  void otherErrorsAreInternal() {
    status = 403;
    assertThrows(
        IllegalStateException.class,
        () -> client().insertJsonEachRow("bromo.raw", "payload", "{}\n", "u", "p", Map.of()));
  }
}
