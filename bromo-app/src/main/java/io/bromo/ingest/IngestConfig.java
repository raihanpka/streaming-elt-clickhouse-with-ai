package io.bromo.ingest;

import io.bromo.shared.ClickHouseClient;
import io.bromo.shared.contract.DataContract;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Builds the validator, parser, and default sink from the data contract and the properties. */
@Configuration
public class IngestConfig {

  /** Validator that follows the data contract. */
  @Bean
  public EventValidator eventValidator(Clock clock, DataContract contract) {
    return new EventValidator(clock, contract);
  }

  /** Parser with the batch limit and the optional plain JSON mapping from the contract. */
  @Bean
  public CloudEventParser cloudEventParser(
      JsonMapper mapper, IngestProperties properties, DataContract contract) {
    return new CloudEventParser(mapper, properties.maxBatchEvents(), contract.plainJson());
  }

  /** Default sink writing to ClickHouse. Define your own EventSink bean to replace it. */
  @Bean
  @ConditionalOnMissingBean(EventSink.class)
  public EventSink eventSink(
      ClickHouseClient client,
      IngestProperties properties,
      DataContract contract,
      JsonMapper mapper) {
    return new ClickHouseEventSink(client, properties, contract, mapper);
  }
}
