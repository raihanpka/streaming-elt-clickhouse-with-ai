package io.bromo.shared.contract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every example contract must load, generate SQL, and match the replayer mapping of the same name.
 */
class ExampleContractsTest {

  static final Path EXAMPLES = Path.of("../config/examples");
  static final Path MAPPINGS = Path.of("../tools/replayer/datasets");

  static List<Path> examples() throws IOException {
    try (Stream<Path> files = Files.list(EXAMPLES)) {
      return files.filter(f -> f.toString().endsWith(".yml")).sorted().toList();
    }
  }

  @Test
  void examplesExist() throws IOException {
    assertFalse(examples().isEmpty());
  }

  @Test
  void everyExampleLoadsAndGeneratesSql() throws IOException {
    for (Path example : examples()) {
      DataContract contract = DataContractLoader.load(example);
      Map<String, String> files = WarehouseSqlGenerator.generate(contract);
      assertTrue(files.get("003_silver.sql").contains(contract.silverTable()), example.toString());
      assertFalse(contract.goldTables().isEmpty(), example.toString());
    }
  }

  @Test
  void everyContractFieldIsFilledByTheMappingOfTheSameName() throws IOException {
    for (Path example : examples()) {
      Path mapping = MAPPINGS.resolve(example.getFileName());
      if (!Files.exists(mapping)) {
        continue;
      }
      DataContract contract = DataContractLoader.load(example);
      Map<String, Object> raw =
          new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(mapping));
      @SuppressWarnings("unchecked")
      Map<String, Object> data =
          (Map<String, Object>) ((Map<String, Object>) raw.get("event")).get("data");
      for (DataContract.FieldSpec field : contract.fields()) {
        assertTrue(
            data.containsKey(field.path()),
            example.getFileName() + ": mapping has no key " + field.path());
      }
    }
  }
}
