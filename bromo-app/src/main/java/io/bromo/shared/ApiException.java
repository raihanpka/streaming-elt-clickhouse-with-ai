package io.bromo.shared;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An error that maps to one RFC 9457 problem response. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final String title;
  private final List<FieldError> errors;
  private final Integer retryAfterSeconds;

  /** Creates an exception with all problem fields. */
  public ApiException(
      HttpStatus status,
      String code,
      String title,
      String detail,
      List<FieldError> errors,
      Integer retryAfterSeconds) {
    super(detail);
    this.status = status;
    this.code = code;
    this.title = title;
    this.errors = errors == null ? List.of() : List.copyOf(errors);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** Malformed JSON or an otherwise invalid request. */
  public static ApiException invalidRequest(String detail) {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail, null, null);
  }

  /** The body is not a valid CloudEvent, with per field errors. */
  public static ApiException invalidEvent(String detail, List<FieldError> errors) {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "invalid-event", "Invalid event", detail, errors, null);
  }

  /** Missing or wrong credential. */
  public static ApiException unauthenticated() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "unauthenticated",
        "Authentication required",
        "A valid bearer credential is required.",
        null,
        null);
  }

  /** The event or batch is larger than the configured limit. */
  public static ApiException contentTooLarge(String detail) {
    return new ApiException(
        HttpStatus.CONTENT_TOO_LARGE, "content-too-large", "Content too large", detail, null, null);
  }

  /** The content type is not accepted by the endpoint. */
  public static ApiException unsupportedMediaType(String detail) {
    return new ApiException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "unsupported-media-type",
        "Unsupported media type",
        detail,
        null,
        null);
  }

  /** A dependency is unavailable, the client should retry. */
  public static ApiException unavailable(String detail, int retryAfterSeconds) {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "unavailable",
        "Service unavailable",
        detail,
        null,
        retryAfterSeconds);
  }

  /** HTTP status of the response. */
  public HttpStatus status() {
    return status;
  }

  /** Short code used as the suffix of the problem type URN. */
  public String code() {
    return code;
  }

  /** Human readable title of the problem. */
  public String title() {
    return title;
  }

  /** Per field errors, empty when none. */
  public List<FieldError> errors() {
    return errors;
  }

  /** Seconds for the Retry-After header, or null. */
  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
