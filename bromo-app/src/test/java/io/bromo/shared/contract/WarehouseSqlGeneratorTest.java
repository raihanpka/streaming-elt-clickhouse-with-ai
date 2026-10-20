package io.bromo.shared.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WarehouseSqlGeneratorTest {

  static final String YAML = defaultYaml();

  static String defaultYaml() {
    try {
      return Files.readString(Path.of("../config/data-contract.yml"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static Map<String, String> generate(String yaml) {
    return WarehouseSqlGenerator.generate(DataContractLoader.parse(yaml));
  }

  @Test
  void generatesTheExpectedFiles() {
    assertEquals(
        java.util.Set.of(
            "001_users.sh",
            "002_bronze.sql",
            "003_silver.sql",
            "004_gold.sql",
            "tests/contract-examples.sh"),
        generate(YAML).keySet());
  }

  @Test
  void eventsTableHasTypedColumnsAndComments() {
    String sql = generate(YAML).get("003_silver.sql");
    assertTrue(sql.contains("`amount` Nullable(Float64)"));
    assertTrue(sql.contains("`label` LowCardinality(String)"));
    assertTrue(
        sql.contains("COMMENT 'Numeric amount from data.amount, NULL when the event has none'"));
    assertTrue(sql.contains("JSONExtract(payload, 'data', 'amount', 'Nullable(Float64)')"));
    assertTrue(sql.contains("JSONExtractString(payload, 'data', 'label')"));
    assertTrue(sql.contains("k NOT IN ('amount', 'label')"));
  }

  @Test
  void retentionAndDatabaseComeFromTheContract() {
    String yaml =
        YAML.replace("bronze: bronze", "bronze: shop_bronze")
            .replace("silver: silver", "silver: shop_silver")
            .replace("  silver: 90", "  silver: 14");
    Map<String, String> files = generate(yaml);
    assertTrue(files.get("003_silver.sql").contains("shop_silver.events"));
    assertTrue(files.get("003_silver.sql").contains("INTERVAL 14 DAY"));
    assertTrue(files.get("001_users.sh").contains("CREATE DATABASE IF NOT EXISTS shop_bronze"));
    assertTrue(!files.get("003_silver.sql").contains(" silver."));
  }

  @Test
  void addingAFieldChangesSilverAndGoldWithoutCodeChanges() {
    String yaml =
        YAML.replace("    dimensions: [label]", "    dimensions: [label, merchant]")
            .replace(
                "  - name: label\n    path: label",
                "  - name: merchant\n    path: merchant\n    type: string\n    nullable: false\n    description: Merchant id\n  - name: label\n    path: label");
    Map<String, String> files = generate(yaml);
    assertTrue(files.get("003_silver.sql").contains("`merchant` String"));
    assertTrue(files.get("004_gold.sql").contains("`merchant` String"));
    assertTrue(
        files.get("004_gold.sql").contains("GROUP BY minute, type, source, label, merchant"));
    assertTrue(files.get("003_silver.sql").contains("k NOT IN ('amount', 'merchant', 'label')"));
  }

  @Test
  void removingAllFieldsStillGeneratesValidStructure() {
    String yaml =
        YAML.substring(0, YAML.indexOf("fields:"))
            + "fields: []\n\ngoldTables:\n  - name: events_1m\n    dimensions: []\n    measures: []\n\nmetrics:\n  - name: event_count\n    goldTable: events_1m\n    unit: events\n    description: Events\n    expression: sum(events)\n    breakdowns: [type, source]\n\nsample:\n  type: com.example.payment.v1\n  data: {}\n";
    Map<String, String> files = generate(yaml);
    assertTrue(files.get("003_silver.sql").contains("`attrs` Map(String, String)"));
    assertTrue(!files.get("003_silver.sql").contains("amount"));
    assertTrue(files.get("004_gold.sql").contains("`events` SimpleAggregateFunction(sum, UInt64)"));
  }

  @Test
  void goldTableHasAggregatesForDeclaredMeasures() {
    String sql = generate(YAML).get("004_gold.sql");
    assertTrue(sql.contains("`amount_sum` SimpleAggregateFunction(sum, Float64)"));
    assertTrue(sql.contains("`amount_p95_state` AggregateFunction(quantile(0.95), Float64)"));
    assertTrue(sql.contains("sumIf(assumeNotNull(amount), isNotNull(amount)) AS amount_sum"));
    assertTrue(
        sql.contains(
            "quantileStateIf(0.95)(assumeNotNull(amount), isNotNull(amount)) AS amount_p95_state"));
    assertTrue(sql.contains("`subjects_state` AggregateFunction(uniq, String)"));
    assertTrue(sql.contains("ORDER BY (type, source, label, minute)"));
  }

  @Test
  void commentsAreEscaped() {
    String yaml =
        YAML.replace(
            "Numeric amount from data.amount, NULL when the event has none", "It's the amount");
    assertTrue(generate(yaml).get("003_silver.sql").contains("COMMENT 'It\\'s the amount'"));
  }

  @Test
  void examplesFileCarriesTheSampleAndChecks() {
    String sh = generate(YAML).get("tests/contract-examples.sh");
    assertTrue(sh.contains("BRONZE='bronze'"));
    assertTrue(sh.contains("GOLD_TABLES=('gold.events_1m')"));
    assertTrue(sh.contains("SAMPLE_TYPE='com.example.payment.v1'"));
    assertTrue(sh.contains("\"amount\":42.5"));
    assertTrue(sh.contains("'amount|42.5'"));
    assertTrue(sh.contains("'label|ok'"));
    assertTrue(sh.contains("'merchant|m1'"));
  }

  @Test
  void committedFilesMatchTheGenerator() throws IOException {
    Map<String, String> files = generate(YAML);
    for (Map.Entry<String, String> file : files.entrySet()) {
      String committed = Files.readString(Path.of("../infra/clickhouse", file.getKey()));
      assertEquals(
          file.getValue(),
          committed,
          file.getKey() + " is out of date. Run ./gradlew :bromo-app:generateWarehouseSql");
    }
  }
}
