package com.campus.studyroom.core;

import com.campus.studyroom.api.RequestIds;
import com.campus.studyroom.security.RequestTelemetry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var telemetry = new RequestTelemetry(req.getHeader("X-Request-ID"));
    RequestIds.set(telemetry.id());
    res.setHeader("X-Request-ID", telemetry.id().toString());
    Throwable failure = null;
    try {
      chain.doFilter(req, res);
    } catch (IOException | ServletException | RuntimeException | Error error) {
      failure = error;
      throw error;
    } finally {
      telemetry.finish(res.getStatus(), !req.getRequestURI().startsWith("/actuator/"), failure);
      RequestIds.clear();
    }
  }
}
