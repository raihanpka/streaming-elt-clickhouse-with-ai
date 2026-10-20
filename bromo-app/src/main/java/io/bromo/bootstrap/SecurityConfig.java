package io.bromo.bootstrap;

import io.bromo.ingest.IngestProperties;
import io.bromo.shared.BearerAuthFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Wires the bearer filters to the paths they protect. */
@Configuration
public class SecurityConfig {

  /** Requires an ingest key on the events endpoint. */
  @Bean
  public FilterRegistrationBean<BearerAuthFilter> ingestAuthFilter(
      IngestProperties properties,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
    FilterRegistrationBean<BearerAuthFilter> registration =
        new FilterRegistrationBean<>(
            new BearerAuthFilter("/api/v1/events", properties.apiKeys(), resolver));
    registration.addUrlPatterns("/api/v1/events", "/api/v1/events/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
