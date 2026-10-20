package io.bromo.shared;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Minimal client for the ClickHouse HTTP interface. */
@Component
public class ClickHouseClient {

  private static final Logger log = LoggerFactory.getLogger(ClickHouseClient.class);
  private static final int RETRY_AFTER_SECONDS = 5;

  private final HttpClient http;
  private final String baseUrl;
  private final Duration timeout;

  /** Creates a client for the configured ClickHouse URL. */
  public ClickHouseClient(ClickHouseProperties properties) {
    this.timeout = Duration.ofSeconds(properties.timeoutSeconds());
    this.baseUrl = properties.url();
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  /** Inserts JSON lines into a table, passing ClickHouse settings as query parameters. */
  public void insertJsonEachRow(
      String table,
      String columns,
      String jsonLines,
      String user,
      String password,
      Map<String, String> settings) {
    String sql = "INSERT INTO " + table + " (" + columns + ") FORMAT JSONEachRow";
    String query =
        "query="
            + encode(sql)
            + settings.entrySet().stream()
                .map(e -> "&" + encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/?" + query))
            .timeout(timeout)
            .header("X-ClickHouse-User", user)
            .header("X-ClickHouse-Key", password)
            .POST(HttpRequest.BodyPublishers.ofString(jsonLines, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = send(request);
    int status = response.statusCode();
    if (status == 502 || status == 503 || status == 504) {
      throw ApiException.unavailable("ClickHouse is unavailable.", RETRY_AFTER_SECONDS);
    }
    if (status != 200) {
      log.error("ClickHouse insert failed: status={} body={}", status, response.body());
      throw new IllegalStateException("ClickHouse insert failed with status " + status);
    }
  }

  /** Sends a request and maps connection problems to a 503. */
  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      log.warn("ClickHouse not reachable: {}", e.toString());
      throw ApiException.unavailable("ClickHouse is unavailable.", RETRY_AFTER_SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.unavailable("ClickHouse request was interrupted.", RETRY_AFTER_SECONDS);
    }
  }

  /** Percent encodes a URL component. */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
