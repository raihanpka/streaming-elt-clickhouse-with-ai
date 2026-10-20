package io.bromo.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Bromo entry point. Hosts the ingest, read, and chat modules. See {@code docs/ARCHITECTURE.md}.
 */
@SpringBootApplication(scanBasePackages = "io.bromo")
@ConfigurationPropertiesScan(basePackages = "io.bromo")
public class BromoApplication {

  /** Starts the application. */
  public static void main(String[] args) {
    SpringApplication.run(BromoApplication.class, args);
  }
}
