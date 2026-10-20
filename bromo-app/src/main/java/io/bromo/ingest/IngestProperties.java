package io.bromo.ingest;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational limits, ClickHouse credentials, and accepted keys. Data rules live in the data
 * contract.
 */
@ConfigurationProperties("bromo.ingest")
public record IngestProperties(
    int maxEventBytes,
    int maxBatchEvents,
    String clickhouseUser,
    String clickhousePassword,
    List<String> apiKeys) {

  /** Applies defaults for missing values. */
  public IngestProperties {
    maxEventBytes = maxEventBytes <= 0 ? 65536 : maxEventBytes;
    maxBatchEvents = maxBatchEvents <= 0 ? 500 : maxBatchEvents;
    clickhouseUser =
        clickhouseUser == null || clickhouseUser.isBlank() ? "ingest_writer" : clickhouseUser;
    clickhousePassword = clickhousePassword == null ? "" : clickhousePassword;
    apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
  }
}
