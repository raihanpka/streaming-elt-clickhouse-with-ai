package io.bromo.shared.contract;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads the data contract once at startup. An invalid contract stops the application with every
 * problem listed.
 */
@Configuration
public class DataContractConfig {

  /** The parsed and validated contract. */
  @Bean
  public DataContract dataContract(ContractProperties properties) {
    return DataContractLoader.load(Path.of(properties.path()));
  }
}
