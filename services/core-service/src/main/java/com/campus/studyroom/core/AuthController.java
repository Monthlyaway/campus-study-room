package com.campus.studyroom.core;

import com.campus.studyroom.api.*;
import com.campus.studyroom.security.SessionPrincipal;
import jakarta.servlet.http.*;
import java.time.Clock;
import java.util.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  final AuthenticationManager manager;
  final AccessService access;
  final SecurityContextRepository repo;
  final Clock clock;
  final RateLimits limits;

  public AuthController(
      AuthenticationManager m,
      AccessService a,
      SecurityContextRepository r,
      Clock c,
      RateLimits l) {
    manager = m;
    access = a;
    repo = r;
    clock = c;
    limits = l;
  }

  @GetMapping("/csrf")
  Object csrf(CsrfToken token) {
    return ApiResponse.of(
        Map.of("csrfToken", token.getToken(), "headerName", token.getHeaderName()));
  }

  @PostMapping("/login")
  Object login(@RequestBody Map<String, Object> b, HttpServletRequest q, HttpServletResponse r) {
    String username = DomainPolicy.text(b.get("username"), 64).toLowerCase(Locale.ROOT);
    String bucket = "login:" + username + ":" + q.getRemoteAddr();
    limits.checkLogin(bucket);
    try {
      Authentication a =
          manager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(
                  username, Objects.toString(b.get("password"), "")));
      UUID id = UUID.fromString(a.getName());
      org.slf4j.MDC.put("actorId", id.toString());
      var u = access.user(id);
      var p = new SessionPrincipal(id, Store.number(u, "authVersion"), clock.instant());
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              p,
              null,
              List.of(
                  new org.springframework.security.core.authority.SimpleGrantedAuthority("USER"))));
      q.getSession();
      q.changeSessionId();
      SecurityContextHolder.setContext(context);
      repo.saveContext(context, q, r);
      new HttpSessionCsrfTokenRepository().saveToken(null, q, r);
      limits.clear(bucket);
      return ApiResponse.of(access.me(id));
    } catch (AuthenticationException e) {
      limits.loginFailure(bucket);
      throw new ApiException(401, "INVALID_CREDENTIALS", "账号或密码错误");
    }
  }

  @GetMapping("/me")
  Object me(Authentication a) {
    return ApiResponse.of(access.me(principal(a).userId()));
  }

  @PostMapping("/logout")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest q) {
    if (q.getSession(false) != null) q.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
  }

  static SessionPrincipal principal(Authentication a) {
    if (a == null || !(a.getPrincipal() instanceof SessionPrincipal p))
      throw new ApiException(401, "UNAUTHENTICATED", "请先登录");
    return p;
  }
}
