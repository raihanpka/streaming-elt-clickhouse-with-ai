package io.bromo.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

class BearerAuthFilterTest {

  final List<Exception> resolved = new ArrayList<>();
  final HandlerExceptionResolver resolver =
      (request, response, handler, ex) -> {
        resolved.add(ex);
        return null;
      };
  final BearerAuthFilter filter =
      new BearerAuthFilter("/api/v1/events", List.of("good-key", "other-key"), resolver);

  MockFilterChain run(String path, String authorization) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRequestURI(path);
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain;
  }

  @Test
  void validKeyPassesThrough() throws Exception {
    MockFilterChain chain = run("/api/v1/events", "Bearer good-key");
    assertNotNull(chain.getRequest());
    assertEquals(0, resolved.size());
  }

  @Test
  void secondKeyAlsoPasses() throws Exception {
    assertNotNull(run("/api/v1/events", "Bearer other-key").getRequest());
  }

  @Test
  void missingHeaderIsUnauthenticated() throws Exception {
    assertNull(run("/api/v1/events", null).getRequest());
    assertEquals(HttpStatus.UNAUTHORIZED, ((ApiException) resolved.get(0)).status());
  }

  @Test
  void wrongKeyIsUnauthenticated() throws Exception {
    assertNull(run("/api/v1/events", "Bearer nope").getRequest());
    assertEquals(1, resolved.size());
  }

  @Test
  void wrongSchemeIsUnauthenticated() throws Exception {
    assertNull(run("/api/v1/events", "Basic Z29vZC1rZXk=").getRequest());
    assertEquals(1, resolved.size());
  }

  @Test
  void otherPathsAreNotChecked() throws Exception {
    assertNotNull(run("/api/v1/metrics", null).getRequest());
    assertEquals(0, resolved.size());
  }

  @Test
  void noConfiguredKeysRejectsEverything() throws Exception {
    BearerAuthFilter empty = new BearerAuthFilter("/api/v1/events", List.of(), resolver);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
    request.setRequestURI("/api/v1/events");
    request.addHeader("Authorization", "Bearer ");
    MockFilterChain chain = new MockFilterChain();
    empty.doFilter(request, new MockHttpServletResponse(), chain);
    assertNull(chain.getRequest());
    assertEquals(1, resolved.size());
  }
}
