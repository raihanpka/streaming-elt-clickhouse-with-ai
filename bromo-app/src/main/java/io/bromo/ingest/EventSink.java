package io.bromo.ingest;

import java.util.List;
import java.util.UUID;

/**
 * Where accepted events are stored.
 *
 * <p>CUSTOMIZE: the default implementation writes to ClickHouse. To store events somewhere else,
 * for example a message broker, provide your own EventSink bean and the default backs off. A sink
 * must return only after the events are durable, because the API answers 200 when this method
 * returns.
 */
public interface EventSink {

  /**
   * Stores the raw event payloads of one request. Throws ApiException 503 when the target is down.
   */
  void write(UUID requestId, List<String> payloads);
}
