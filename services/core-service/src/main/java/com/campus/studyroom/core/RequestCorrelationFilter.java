package com.campus.studyroom.core;

import com.campus.studyroom.api.RequestIds;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
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
    UUID id;
    try {
      id = UUID.fromString(req.getHeader("X-Request-ID"));
    } catch (Exception e) {
      id = UUID.randomUUID();
    }
    RequestIds.set(id);
    res.setHeader("X-Request-ID", id.toString());
    org.slf4j.MDC.put("requestId", id.toString());
    try {
      chain.doFilter(req, res);
    } finally {
      RequestIds.clear();
      org.slf4j.MDC.remove("requestId");
    }
  }
}
