package io.bromo.shared.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataContractLoaderTest {

  static String defaultYaml() throws IOException {
    return Files.readString(Path.of("../config/data-contract.yml"));
  }

  static String problems(String yaml) {
    return assertThrows(InvalidDataContractException.class, () -> DataContractLoader.parse(yaml))
        .getMessage();
  }

  @Test
  void defaultContractLoads() throws IOException {
    DataContract contract = DataContractLoader.parse(defaultYaml());
    assertEquals("bronze", contract.layers().bronze());
    assertEquals("silver.events", contract.silverTable());
    assertEquals("gold.events_1m", contract.goldTable(contract.goldTables().get(0)));
    assertEquals(2, contract.fields().size());
    assertEquals(FieldType.FLOAT64, contract.fields().get(0).type());
    assertTrue(contract.fields().get(0).nullable());
    assertEquals(List.of("label"), contract.goldTables().get(0).dimensions());
    assertEquals(3, contract.metrics().size());
    assertEquals("com.example.payment.v1", contract.sample().type());
    assertEquals(42.5, contract.sample().data().get("amount"));
  }

  @Test
  void typePatternIsApplied() throws IOException {
    DataContract contract = DataContractLoader.parse(defaultYaml());
    assertTrue(contract.event().typePattern().matcher("a.b.v1").matches());
    assertTrue(!contract.event().typePattern().matcher("Payment").matches());
  }

  @Test
  void unknownFieldTypeIsReported() throws IOException {
    assertTrue(
        problems(defaultYaml().replace("type: float64", "type: decimal"))
            .contains("fields[0].type"));
  }

  @Test
  void duplicateFieldNamesAreReported() throws IOException {
    assertTrue(
        problems(defaultYaml().replace("name: label", "name: amount")).contains("duplicate"));
  }

  @Test
  void reservedColumnNamesAreRejected() throws IOException {
    assertTrue(problems(defaultYaml().replace("name: label", "name: attrs")).contains("reserved"));
  }

  @Test
  void invalidNamesAreRejected() throws IOException {
    assertTrue(
        problems(defaultYaml().replace("name: label", "name: Bad-Name"))
            .contains("fields[1].name"));
  }

  @Test
  void goldDimensionMustBeAStringField() throws IOException {
    assertTrue(
        problems(defaultYaml().replace("dimensions: [label]", "dimensions: [amount]"))
            .contains("goldTables[0].dimensions"));
    assertTrue(
        problems(defaultYaml().replace("dimensions: [label]", "dimensions: [nope]"))
            .contains("goldTables[0].dimensions"));
  }

  @Test
  void goldDimensionMustNotBeNullable() throws IOException {
    String yaml =
        defaultYaml()
            .replace(
                "type: low_cardinality_string\n    nullable: false",
                "type: low_cardinality_string\n    nullable: true");
    assertTrue(problems(yaml).contains("must not be nullable"));
  }

  @Test
  void goldMeasureMustBeNumericWithKnownFunctions() throws IOException {
    assertTrue(
        problems(defaultYaml().replace("field: amount", "field: label"))
            .contains("goldTables[0].measures"));
    assertTrue(
        problems(defaultYaml().replace("[sum, p95]", "[median]"))
            .contains("goldTables[0].measures"));
  }

  @Test
  void metricBreakdownMustBeADimension() throws IOException {
    assertTrue(
        problems(
                defaultYaml()
                    .replace(
                        "breakdowns: [type, source, label]\n  - name: amount_sum",
                        "breakdowns: [nope]\n  - name: amount_sum"))
            .contains("metrics[0].breakdowns"));
  }

  @Test
  void invalidTypePatternIsReported() throws IOException {
    assertTrue(
        problems(defaultYaml().replace("'^[a-z0-9][a-z0-9._-]*\\.v[0-9]+$'", "'['"))
            .contains("event.typePattern"));
  }

  @Test
  void sampleMustMatchTheTypePattern() throws IOException {
    assertTrue(
        problems(
                defaultYaml()
                    .replace("type: com.example.payment.v1\n  source", "type: Bad\n  source"))
            .contains("sample.type"));
  }

  @Test
  void plainJsonBindingsAreParsed() throws IOException {
    String yaml =
        defaultYaml()
            .replace(
                "# intake:\n#   plainJson:\n#     id:      { pointer: /orderId }\n#     time:    { pointer: /createdAt }\n#     source:  { constant: shop-backend }\n#     type:    { constant: com.shop.order.v1 }\n",
                "intake:\n  plainJson:\n    id: { pointer: /orderId }\n    time: { pointer: /createdAt }\n    source: { constant: shop-backend }\n    type: { constant: com.shop.order.v1 }\n");
    DataContract contract = DataContractLoader.parse(yaml);
    assertEquals("/orderId", contract.plainJson().id().pointer());
    assertEquals("shop-backend", contract.plainJson().source().constant());
  }

  @Test
  void plainJsonBindingNeedsExactlyOneOfPointerOrConstant() throws IOException {
    String yaml =
        defaultYaml()
            .replace(
                "# intake:\n#   plainJson:\n#     id:      { pointer: /orderId }\n#     time:    { pointer: /createdAt }\n#     source:  { constant: shop-backend }\n#     type:    { constant: com.shop.order.v1 }\n",
                "intake:\n  plainJson:\n    id: { pointer: /orderId, constant: x }\n    time: { pointer: /createdAt }\n    source: { constant: s }\n    type: { constant: t.v1 }\n");
    assertTrue(problems(yaml).contains("intake.plainJson.id"));
  }

  @Test
  void allProblemsAreReportedTogether() throws IOException {
    String message =
        problems(
            defaultYaml()
                .replace("type: float64", "type: decimal")
                .replace("name: label", "name: attrs"));
    assertTrue(message.contains("fields[0].type") && message.contains("reserved"));
  }

  @Test
  void layerNamesMustBeDifferent() throws IOException {
    assertTrue(
        problems(defaultYaml().replace("silver: silver", "silver: bronze"))
            .contains("three different names"));
  }

  @Test
  void layerNamesCanBePrefixed() throws IOException {
    DataContract contract =
        DataContractLoader.parse(defaultYaml().replace("bronze: bronze", "bronze: shop_bronze"));
    assertEquals("shop_bronze.events", contract.bronzeTable());
  }

  @Test
  void metricMustPointToAnExistingGoldTable() throws IOException {
    assertTrue(
        problems(
                defaultYaml()
                    .replace(
                        "    goldTable: events_1m\n    unit: events",
                        "    goldTable: nope\n    unit: events"))
            .contains("metrics[0].goldTable"));
  }

  @Test
  void severalGoldTablesAreSupportedAndNamesMustBeUnique() throws IOException {
    String second = "  - name: events_5m\n    dimensions: []\n    measures: []\n";
    String yaml = defaultYaml().replace("# Metrics exposed", second + "\n# Metrics exposed");
    assertEquals(2, DataContractLoader.parse(yaml).goldTables().size());
    assertTrue(problems(yaml.replace("events_5m", "events_1m")).contains("duplicate"));
  }
}
