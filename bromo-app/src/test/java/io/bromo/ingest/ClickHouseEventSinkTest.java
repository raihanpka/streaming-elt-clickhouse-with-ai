package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.bromo.shared.ClickHouseClient;
import io.bromo.shared.contract.DataContract;
import io.bromo.testsupport.TestContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClickHouseEventSinkTest {

  @Test
  void writesOneJsonLinePerEventWithAsyncInsertSettings() {
    ClickHouseClient client = mock(ClickHouseClient.class);
    JsonMapper mapper = JsonMapper.builder().build();
    IngestProperties props = new IngestProperties(0, 0, "ingest_writer", "pw", List.of());
    DataContract contract = TestContract.load();
    ClickHouseEventSink writer = new ClickHouseEventSink(client, props, contract, mapper);
    UUID id = UUID.randomUUID();

    writer.write(id, List.of("{\"id\":\"a\"}", "{\"id\":\"b\"}"));

    ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> settings = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .insertJsonEachRow(
            eq(contract.database() + ".raw"),
            eq("request_id, payload"),
            lines.capture(),
            eq("ingest_writer"),
            eq("pw"),
            settings.capture());
    String[] rows = lines.getValue().split("\n");
    assertEquals(2, rows.length);
    JsonNode first = mapper.readTree(rows[0]);
    assertEquals(id.toString(), first.get("request_id").asString());
    assertEquals("{\"id\":\"a\"}", first.get("payload").asString());
    assertEquals("1", settings.getValue().get("async_insert"));
    assertEquals("1", settings.getValue().get("wait_for_async_insert"));
    assertEquals("1", settings.getValue().get("async_insert_deduplicate"));
  }
}
