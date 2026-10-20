package io.bromo.testsupport;

import io.bromo.shared.contract.DataContract;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds valid events from the sample in the data contract, so tests follow the user's data. */
public final class TestEvents {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private TestEvents() {}

  /** A valid event tree built from the contract sample. */
  public static ObjectNode tree(DataContract contract, String id, Instant time) {
    DataContract.Sample sample = contract.sample();
    ObjectNode event = MAPPER.createObjectNode();
    event.put("specversion", "1.0");
    event.put("id", id);
    event.put("source", sample.source());
    event.put("type", sample.type());
    event.put("time", time.toString());
    if (sample.subject() != null) {
      event.put("subject", sample.subject());
    }
    event.set("data", MAPPER.valueToTree(sample.data()));
    return event;
  }

  /** A valid event as JSON text, using the real contract. */
  public static String json(String id, Instant time) {
    return tree(TestContract.load(), id, time).toString();
  }

  /** A valid event as JSON text stamped a few seconds ago. */
  public static String recent(String id) {
    return json(id, Instant.now().minusSeconds(10));
  }
}
