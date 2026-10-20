package io.bromo.ingest;

import io.bromo.shared.ClickHouseClient;
import io.bromo.shared.contract.DataContract;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Default sink: writes payloads to the bronze table with asynchronous inserts that wait for the
 * flush.
 */
public class ClickHouseEventSink implements EventSink {

  private static final String COLUMNS = "request_id, payload";
  private static final Map<String, String> SETTINGS = settings();

  private final ClickHouseClient client;
  private final IngestProperties properties;
  private final JsonMapper mapper;
  private final String table;

  /** Creates a sink using the ingest_writer ClickHouse user and the database from the contract. */
  public ClickHouseEventSink(
      ClickHouseClient client,
      IngestProperties properties,
      DataContract contract,
      JsonMapper mapper) {
    this.client = client;
    this.properties = properties;
    this.mapper = mapper;
    this.table = contract.database() + ".raw";
  }

  /** Stores one raw row per payload, all under one request id. */
  @Override
  public void write(UUID requestId, List<String> payloads) {
    StringBuilder lines = new StringBuilder();
    for (String payload : payloads) {
      ObjectNode row = mapper.createObjectNode();
      row.put("request_id", requestId.toString());
      row.put("payload", payload);
      lines.append(mapper.writeValueAsString(row)).append('\n');
    }
    client.insertJsonEachRow(
        table,
        COLUMNS,
        lines.toString(),
        properties.clickhouseUser(),
        properties.clickhousePassword(),
        SETTINGS);
  }

  /** ClickHouse settings: acknowledge after the flush and deduplicate retried batches. */
  private static Map<String, String> settings() {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("async_insert", "1");
    settings.put("wait_for_async_insert", "1");
    settings.put("async_insert_deduplicate", "1");
    return Map.copyOf(settings);
  }
}
