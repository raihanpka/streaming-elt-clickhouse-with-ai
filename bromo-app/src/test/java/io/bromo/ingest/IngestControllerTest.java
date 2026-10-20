package io.bromo.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.bromo.shared.ApiException;
import io.bromo.shared.ProblemDetailsHandler;
import io.bromo.shared.contract.DataContract;
import io.bromo.testsupport.TestContract;
import io.bromo.testsupport.TestEvents;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Controller tests use events built from the data contract sample. */
class IngestControllerTest {

  static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

  final DataContract contract = TestContract.load();
  final EventSink sink = mock(EventSink.class);
  MockMvc mvc;

  String event(String id) {
    return TestEvents.tree(contract, id, NOW.minusSeconds(60)).toString();
  }

  @BeforeEach
  void setUp() {
    JsonMapper mapper = JsonMapper.builder().build();
    IngestProperties props = new IngestProperties(2000, 2, "ingest_writer", "pw", List.of("k"));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    IngestService service =
        new IngestService(
            new CloudEventParser(mapper, 2, contract.plainJson()),
            new EventValidator(clock, contract),
            sink,
            mapper,
            props);
    mvc =
        MockMvcBuilders.standaloneSetup(new IngestController(service))
            .setControllerAdvice(new ProblemDetailsHandler())
            .build();
  }

  MockHttpServletRequestBuilder structured(String body) {
    return post("/api/v1/events").contentType("application/cloudevents+json").content(body);
  }

  @Test
  void structuredEventIsStoredAndCounted() throws Exception {
    mvc.perform(structured(event("e1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(1));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> payloads = ArgumentCaptor.forClass(List.class);
    verify(sink).write(any(), payloads.capture());
    assertEquals(1, payloads.getValue().size());
    assertTrue(payloads.getValue().get(0).contains("\"id\":\"e1\""));
  }

  @Test
  void batchIsStoredInOneWrite() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/cloudevents-batch+json")
                .content("[" + event("e1") + "," + event("e2") + "]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(2));
    verify(sink).write(any(), anyList());
  }

  @Test
  void binaryModeIsStored() throws Exception {
    ObjectNode tree = TestEvents.tree(contract, "b1", NOW.minusSeconds(60));
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/json")
                .header("ce-specversion", "1.0")
                .header("ce-id", "b1")
                .header("ce-source", tree.get("source").asString())
                .header("ce-type", tree.get("type").asString())
                .header("ce-time", tree.get("time").asString())
                .content(tree.get("data").toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(1));
  }

  @Test
  void invalidEventReturnsPointersAndStoresNothing() throws Exception {
    mvc.perform(structured("{\"specversion\":\"1.0\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("urn:bromo:problem:invalid-event"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"));
    verify(sink, never()).write(any(), anyList());
  }

  @Test
  void batchErrorsUseIndexedPointers() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/cloudevents-batch+json")
                .content("[" + event("e1") + ",{\"specversion\":\"1.0\"}]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/1/id"));
    verify(sink, never()).write(any(), anyList());
  }

  @Test
  void malformedJsonIsBadRequest() throws Exception {
    mvc.perform(structured("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("urn:bromo:problem:invalid-request"));
  }

  @Test
  void wrongContentTypeIsUnsupported() throws Exception {
    mvc.perform(post("/api/v1/events").contentType("text/plain").content("x"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void oversizeEventIsTooLarge() throws Exception {
    ObjectNode big = TestEvents.tree(contract, "big", NOW.minusSeconds(60));
    ((ObjectNode) big.get("data")).put("padding", "x".repeat(3000));
    mvc.perform(structured(big.toString())).andExpect(status().isContentTooLarge());
  }

  @Test
  void tooManyBatchEventsIsTooLarge() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/cloudevents-batch+json")
                .content("[" + event("a") + "," + event("b") + "," + event("c") + "]"))
        .andExpect(status().isContentTooLarge());
  }

  @Test
  void sinkOutageIsServiceUnavailableWithRetryAfter() throws Exception {
    doThrow(ApiException.unavailable("ClickHouse is unavailable.", 5))
        .when(sink)
        .write(any(), anyList());
    mvc.perform(structured(event("e1")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"));
  }
}
