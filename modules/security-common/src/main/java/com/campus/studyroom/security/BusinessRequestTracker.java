package com.campus.studyroom.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

/** Counts admitted business requests so deployment can wait before stopping either service. */
public class BusinessRequestTracker extends OncePerRequestFilter {
  private final AtomicInteger active = new AtomicInteger();

  public int activeCount() {
    return active.get();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/") || path.endsWith("/version");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    active.incrementAndGet();
    try {
      chain.doFilter(request, response);
    } finally {
      active.decrementAndGet();
    }
  }
}
