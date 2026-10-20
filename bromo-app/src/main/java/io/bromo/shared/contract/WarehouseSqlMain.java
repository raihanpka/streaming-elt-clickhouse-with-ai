package io.bromo.shared.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Command line entry: writes the generated warehouse SQL for a contract file. */
public final class WarehouseSqlMain {

  private WarehouseSqlMain() {}

  /** Usage: WarehouseSqlMain CONTRACT_FILE OUTPUT_DIR. */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("usage: WarehouseSqlMain CONTRACT_FILE OUTPUT_DIR");
      System.exit(2);
    }
    DataContract contract = DataContractLoader.load(Path.of(args[0]));
    for (Map.Entry<String, String> file : WarehouseSqlGenerator.generate(contract).entrySet()) {
      Path target = Path.of(args[1]).resolve(file.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, file.getValue());
      if (file.getKey().endsWith(".sh") && !file.getKey().startsWith("tests/")) {
        target.toFile().setExecutable(true);
      }
      System.out.println("wrote " + target);
    }
  }
}
