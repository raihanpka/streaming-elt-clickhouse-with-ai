package io.bromo.shared.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Location of the data contract file. */
@ConfigurationProperties("bromo.contract")
public record ContractProperties(String path) {

  /** Defaults to config/data-contract.yml in the working directory. */
  public ContractProperties {
    path = path == null || path.isBlank() ? "config/data-contract.yml" : path;
  }
}
