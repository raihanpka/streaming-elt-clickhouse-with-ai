package io.bromo.testsupport;

import io.bromo.shared.contract.DataContract;
import io.bromo.shared.contract.DataContractLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the real data contract so tests follow whatever the user put in config/data-contract.yml.
 */
public final class TestContract {

  private static final Path FILE = Path.of("../config/data-contract.yml");

  private TestContract() {}

  /** The contract as text, for tests that modify it. */
  public static String yaml() {
    try {
      return Files.readString(FILE);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The parsed contract. */
  public static DataContract load() {
    return DataContractLoader.parse(yaml());
  }
}
