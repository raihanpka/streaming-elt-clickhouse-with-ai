package io.bromo.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** A throwaway ClickHouse that runs the real init scripts from infra/clickhouse. */
public final class ClickHouseFixture implements AutoCloseable {

  public static final String ADMIN_PASSWORD = "admin-test-pw";
  public static final String INGEST_PASSWORD = "ingest-test-pw";
  public static final String API_PASSWORD = "api-test-pw";

  private final GenericContainer<?> container;

  /** Starts the container with the image pinned in the compose file. */
  public ClickHouseFixture() {
    container =
        new GenericContainer<>(DockerImageName.parse(imageFromCompose()))
            .withExposedPorts(8123)
            .withEnv("CLICKHOUSE_USER", "admin")
            .withEnv("CLICKHOUSE_PASSWORD", ADMIN_PASSWORD)
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withEnv("CLICKHOUSE_INGEST_PASSWORD", INGEST_PASSWORD)
            .withEnv("CLICKHOUSE_API_PASSWORD", API_PASSWORD)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));
    for (String script : new String[] {"001_users.sh", "002_raw.sql", "003_events.sql"}) {
      container.withCopyFileToContainer(
          MountableFile.forHostPath(Path.of("../infra/clickhouse", script), 0755),
          "/docker-entrypoint-initdb.d/" + script);
    }
    container.start();
  }

  /** Base URL of the HTTP interface. */
  public String url() {
    return "http://" + container.getHost() + ":" + container.getMappedPort(8123);
  }

  /** Runs a query as the admin user and returns the trimmed text result. */
  public String query(String sql) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(url() + "/?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8)))
              .header("X-ClickHouse-User", "admin")
              .header("X-ClickHouse-Key", ADMIN_PASSWORD)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("query failed: " + response.body());
      }
      return response.body().trim();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Stops the container to simulate an outage. */
  public void stop() {
    container.stop();
  }

  /** Removes the container. */
  @Override
  public void close() {
    container.stop();
  }

  /** Reads the ClickHouse image tag from the compose file so tests and compose cannot drift. */
  private static String imageFromCompose() {
    try {
      String compose = Files.readString(Path.of("../infra/docker/docker-compose.yml"));
      Matcher m = Pattern.compile("image:\\s*(clickhouse/clickhouse-server:\\S+)").matcher(compose);
      if (!m.find()) {
        throw new IllegalStateException("no clickhouse image in compose file");
      }
      return m.group(1);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
