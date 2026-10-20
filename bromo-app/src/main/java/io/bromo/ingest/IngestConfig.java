package io.bromo.ingest;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Builds the parser and validator from the ingest properties. */
@Configuration
public class IngestConfig {

  /** Validator using the retention window from the properties. */
  @Bean
  public EventValidator eventValidator(Clock clock, IngestProperties properties) {
    return new EventValidator(clock, properties.retentionDays());
  }

  /** Parser using the batch limit from the properties. */
  @Bean
  public CloudEventParser cloudEventParser(JsonMapper mapper, IngestProperties properties) {
    return new CloudEventParser(mapper, properties.maxBatchEvents());
  }
}
