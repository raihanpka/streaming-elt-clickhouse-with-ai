package io.bromo.ingest;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for CloudEvents ingestion. */
@RestController
@RequestMapping("/api/v1/events")
public class IngestController {

  private final IngestService service;

  /** Creates the controller. */
  public IngestController(IngestService service) {
    this.service = service;
  }

  /** Stores the events in the request and answers with the accepted count. */
  @PostMapping
  public IngestResponse ingest(
      HttpServletRequest request,
      @RequestHeader HttpHeaders headers,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType)
      throws IOException {
    return new IngestResponse(service.ingest(contentType, headers, request.getInputStream()));
  }
}
