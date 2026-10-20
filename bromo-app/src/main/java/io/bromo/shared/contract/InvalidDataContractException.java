package io.bromo.shared.contract;

import java.util.List;

/** Thrown when the data contract file is invalid, with every problem found. */
public class InvalidDataContractException extends RuntimeException {

  private final List<String> problems;

  /** Creates the exception from the list of problems. */
  public InvalidDataContractException(List<String> problems) {
    super("Invalid data contract:\n  * " + String.join("\n  * ", problems));
    this.problems = List.copyOf(problems);
  }

  /** The individual problems. */
  public List<String> problems() {
    return problems;
  }
}
