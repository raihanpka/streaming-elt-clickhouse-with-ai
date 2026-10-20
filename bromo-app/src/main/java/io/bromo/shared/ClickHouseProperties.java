package io.bromo.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings for the ClickHouse HTTP interface. */
@ConfigurationProperties("bromo.clickhouse")
public record ClickHouseProperties(String url, int timeoutSeconds) {

  /** Applies defaults for missing values. */
  public ClickHouseProperties {
    url = url == null || url.isBlank() ? "http://localhost:8123" : url;
    timeoutSeconds = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
  }
}
