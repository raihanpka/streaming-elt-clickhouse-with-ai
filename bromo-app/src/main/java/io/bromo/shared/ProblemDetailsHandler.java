package io.bromo.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns every error into an RFC 9457 problem response, without internal details. */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);
  private static final String TYPE_PREFIX = "urn:bromo:problem:";

  /** Maps an ApiException to its problem response. */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
    ProblemDetail problem = problem(ex.status(), ex.code(), ex.title(), ex.getMessage(), request);
    if (!ex.errors().isEmpty()) {
      problem.setProperty("errors", ex.errors());
    }
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (ex.status() == HttpStatus.UNAUTHORIZED) {
      response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"bromo\"");
    }
    if (ex.retryAfterSeconds() != null) {
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
    }
    return response.body(problem);
  }

  /** Maps any other exception to a generic 500 and logs the cause. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("Unhandled error on {}", request.getRequestURI(), ex);
    ProblemDetail problem =
        problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Internal error",
            "An unexpected error occurred.",
            request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /** Builds the common problem fields. */
  private static ProblemDetail problem(
      HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_PREFIX + code));
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    return problem;
  }
}
