package io.bromo.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Requires a bearer key on one path prefix, comparing in constant time. */
public class BearerAuthFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final String pathPrefix;
  private final List<byte[]> keyDigests;
  private final HandlerExceptionResolver resolver;

  /** Creates a filter for a path prefix and the accepted keys. */
  public BearerAuthFilter(String pathPrefix, List<String> keys, HandlerExceptionResolver resolver) {
    this.pathPrefix = pathPrefix;
    this.keyDigests =
        keys.stream().filter(k -> !k.isBlank()).map(BearerAuthFilter::digest).toList();
    this.resolver = resolver;
  }

  /** Skips requests outside the protected path prefix. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(pathPrefix);
  }

  /**
   * Lets the request through when the key is valid, otherwise hands a 401 to the problem handler.
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isAuthorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
      chain.doFilter(request, response);
    } else {
      resolver.resolveException(request, response, null, ApiException.unauthenticated());
    }
  }

  /** Checks the Authorization header against every configured key without early exit. */
  private boolean isAuthorized(String header) {
    if (header == null || !header.startsWith(PREFIX)) {
      return false;
    }
    byte[] candidate = digest(header.substring(PREFIX.length()));
    boolean match = false;
    for (byte[] key : keyDigests) {
      match |= MessageDigest.isEqual(candidate, key);
    }
    return match;
  }

  /** Hashes a secret so every comparison has the same length. */
  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
