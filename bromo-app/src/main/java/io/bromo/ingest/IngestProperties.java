package io.bromo.ingest;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits, ClickHouse credentials, and accepted keys of the ingest module. */
@ConfigurationProperties("bromo.ingest")
public record IngestProperties(
    int maxEventBytes,
    int maxBatchEvents,
    int retentionDays,
    String clickhouseUser,
    String clickhousePassword,
    List<String> apiKeys) {

  /** Applies defaults for missing values. */
  public IngestProperties {
    maxEventBytes = maxEventBytes <= 0 ? 65536 : maxEventBytes;
    maxBatchEvents = maxBatchEvents <= 0 ? 500 : maxBatchEvents;
    retentionDays = retentionDays <= 0 ? 90 : retentionDays;
    clickhouseUser =
        clickhouseUser == null || clickhouseUser.isBlank() ? "ingest_writer" : clickhouseUser;
    clickhousePassword = clickhousePassword == null ? "" : clickhousePassword;
    apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
  }
}
