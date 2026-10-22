package com.campus.studyroom.core;

import com.campus.studyroom.core.persistence.AccountRepository;
import com.campus.studyroom.security.SessionPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    var e = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
    return new org.springframework.security.crypto.password.DelegatingPasswordEncoder(
        "bcrypt", Map.of("bcrypt", e));
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  DefaultCookieSerializer cookie(@Value("${server.servlet.session.cookie.secure}") boolean secure) {
    var c = new DefaultCookieSerializer();
    c.setCookieName("STUDYROOMSESSION");
    c.setCookiePath("/");
    c.setUseHttpOnlyCookie(true);
    c.setUseSecureCookie(secure);
    c.setSameSite("Lax");
    return c;
  }

  @Bean
  AuthenticationManager authenticationManager(AccountRepository accounts, PasswordEncoder encoder) {
    UserDetailsService uds =
        username -> {
          var u =
              accounts
                  .findByUsername(username.toLowerCase(Locale.ROOT))
                  .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
          return User.withUsername(u.id.toString())
              .password(u.passwordHash)
              .disabled(!u.enabled)
              .authorities("USER")
              .build();
        };
    var provider = new DaoAuthenticationProvider(uds);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
  }

  @Bean
  @Order(1)
  SecurityFilterChain internal(
      HttpSecurity http,
      PasswordEncoder encoder,
      @Value("${studyroom.internal-user}") String username,
      @Value("${studyroom.internal-password}") String password)
      throws Exception {
    if (password.isBlank()) throw new IllegalStateException("INTERNAL_PASSWORD must be configured");
    var users =
        new org.springframework.security.provisioning.InMemoryUserDetailsManager(
            User.withUsername(username)
                .password(encoder.encode(password))
                .authorities("SERVICE")
                .build());
    var provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    return http.securityMatcher("/internal/**")
        .authenticationProvider(provider)
        .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("SERVICE"))
        .httpBasic(c -> {})
        .csrf(c -> c.disable())
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain web(HttpSecurity http, AccessService access, SecurityContextRepository repo)
      throws Exception {
    var csrf = new HttpSessionCsrfTokenRepository();
    csrf.setHeaderName("X-CSRF-TOKEN");
    var handler = new CsrfTokenRequestAttributeHandler();
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login",
                        "/api/v1/version",
                        "/actuator/health/**",
                        "/actuator/jobs")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
        .securityContext(c -> c.securityContextRepository(repo))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> writeError(r, 401, "UNAUTHENTICATED", "请先登录"))
                    .accessDeniedHandler(
                        (q, r, x) -> writeError(r, 403, "FORBIDDEN", "无此操作权限或安全令牌已过期")))
        .addFilterBefore(
            new OncePerRequestFilter() {
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                var a = SecurityContextHolder.getContext().getAuthentication();
                if (a != null && a.getPrincipal() instanceof SessionPrincipal p) {
                  org.slf4j.MDC.put("actorId", p.userId().toString());
                }
                if (a != null
                    && a.getPrincipal() instanceof SessionPrincipal p
                    && !access.valid(p)) {
                  SecurityContextHolder.clearContext();
                  if (req.getSession(false) != null) req.getSession(false).invalidate();
                  writeError(res, 401, "SESSION_EXPIRED", "登录状态已变化，请重新登录");
                  return;
                }
                chain.doFilter(req, res);
              }
            },
            AuthorizationFilter.class)
        .build();
  }

  static void writeError(HttpServletResponse r, int status, String code, String message)
      throws IOException {
    org.slf4j.MDC.put("errorCode", code);
    r.setStatus(status);
    r.setContentType("application/json;charset=UTF-8");
    r.getWriter()
        .write(
            Store.json(
                Map.of(
                    "error",
                    Map.of("code", code, "message", message, "details", Map.of()),
                    "requestId",
                    com.campus.studyroom.api.RequestIds.current().toString())));
  }
}
