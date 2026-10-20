package io.bromo.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ProblemDetailsHandlerTest {

  @RestController
  static class Thrower {
    @GetMapping("/invalid")
    String invalid() {
      throw ApiException.invalidEvent(
          "not valid", List.of(new FieldError("/id", "must not be empty")));
    }

    @GetMapping("/unauth")
    String unauth() {
      throw ApiException.unauthenticated();
    }

    @GetMapping("/down")
    String down() {
      throw ApiException.unavailable("ClickHouse is unavailable.", 5);
    }

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("secret internal detail");
    }
  }

  MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new ProblemDetailsHandler())
            .build();
  }

  @Test
  void invalidEventHasTypeErrorsAndProblemContentType() throws Exception {
    mvc.perform(get("/invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("urn:bromo:problem:invalid-event"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.instance").value("/invalid"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/id"))
        .andExpect(jsonPath("$.errors[0].detail").value("must not be empty"));
  }

  @Test
  void unauthenticatedAddsWwwAuthenticate() throws Exception {
    mvc.perform(get("/unauth"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"bromo\""))
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  void unavailableAddsRetryAfter() throws Exception {
    mvc.perform(get("/down"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"));
  }

  @Test
  void unexpectedErrorsDoNotLeakDetails() throws Exception {
    mvc.perform(get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.type").value("urn:bromo:problem:internal-error"))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
  }
}
