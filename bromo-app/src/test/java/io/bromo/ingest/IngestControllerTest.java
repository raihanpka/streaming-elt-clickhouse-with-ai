package io.bromo.ingest;

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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class IngestControllerTest {

  static final String EVENT =
      "{\"specversion\":\"1.0\",\"id\":\"e1\",\"source\":\"s\",\"type\":\"com.example.payment.v1\","
          + "\"time\":\"2026-10-20T09:59:00Z\",\"data\":{\"amount\":42.5}}";

  RawEventWriter writer = mock(RawEventWriter.class);
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    JsonMapper mapper = JsonMapper.builder().build();
    IngestProperties props = new IngestProperties(400, 2, 90, "ingest_writer", "pw", List.of("k"));
    Clock clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC);
    IngestService service =
        new IngestService(
            new CloudEventParser(mapper, 2), new EventValidator(clock, 90), writer, mapper, props);
    mvc =
        MockMvcBuilders.standaloneSetup(new IngestController(service))
            .setControllerAdvice(new ProblemDetailsHandler())
            .build();
  }

  @Test
  void structuredEventIsStoredAndCounted() throws Exception {
    mvc.perform(post("/api/v1/events").contentType("application/cloudevents+json").content(EVENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(1));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> payloads = ArgumentCaptor.forClass(List.class);
    verify(writer).write(any(), payloads.capture());
    org.junit.jupiter.api.Assertions.assertEquals(1, payloads.getValue().size());
    org.junit.jupiter.api.Assertions.assertTrue(
        payloads.getValue().get(0).contains("\"id\":\"e1\""));
  }

  @Test
  void batchIsStoredInOneWrite() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/cloudevents-batch+json")
                .content("[" + EVENT + "," + EVENT.replace("e1", "e2") + "]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(2));
  }

  @Test
  void binaryModeIsStored() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/json")
                .header("ce-specversion", "1.0")
                .header("ce-id", "b1")
                .header("ce-source", "s")
                .header("ce-type", "a.v1")
                .header("ce-time", "2026-10-20T09:59:00Z")
                .content("{\"amount\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(1));
  }

  @Test
  void invalidEventReturnsPointersAndStoresNothing() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/cloudevents+json")
                .content("{\"specversion\":\"1.0\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("urn:bromo:problem:invalid-event"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"));
    verify(writer, never()).write(any(), anyList());
  }

  @Test
  void batchErrorsUseIndexedPointers() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/cloudevents-batch+json")
                .content("[" + EVENT + ",{\"specversion\":\"1.0\"}]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/1/id"));
    verify(writer, never()).write(any(), anyList());
  }

  @Test
  void malformedJsonIsBadRequest() throws Exception {
    mvc.perform(post("/api/v1/events").contentType("application/cloudevents+json").content("{oops"))
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
    String big = EVENT.replace("42.5", "\"" + "x".repeat(500) + "\"");
    mvc.perform(post("/api/v1/events").contentType("application/cloudevents+json").content(big))
        .andExpect(status().isContentTooLarge());
  }

  @Test
  void tooManyBatchEventsIsTooLarge() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType("application/cloudevents-batch+json")
                .content("[" + EVENT + "," + EVENT + "," + EVENT + "]"))
        .andExpect(status().isContentTooLarge());
  }

  @Test
  void clickhouseDownIsServiceUnavailableWithRetryAfter() throws Exception {
    doThrow(ApiException.unavailable("ClickHouse is unavailable.", 5))
        .when(writer)
        .write(any(), anyList());
    mvc.perform(post("/api/v1/events").contentType("application/cloudevents+json").content(EVENT))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"));
  }
}
