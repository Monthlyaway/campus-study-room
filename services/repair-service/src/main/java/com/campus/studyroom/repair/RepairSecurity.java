package com.campus.studyroom.repair;

import com.campus.studyroom.security.SessionPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class RepairSecurity {
    @Bean org.springframework.security.core.userdetails.UserDetailsService noLocalLogin(){return username->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Authentication is provided by core-service");};}
    @Bean DefaultCookieSerializer cookieSerializer(@Value("${studyroom.cookie-secure:false}") boolean secure) {
        DefaultCookieSerializer serializer=new DefaultCookieSerializer(); serializer.setCookieName("PJSESSION");
        serializer.setCookiePath("/"); serializer.setUseHttpOnlyCookie(true); serializer.setUseSecureCookie(secure); serializer.setSameSite("Lax"); return serializer;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, CoreClient core, Clock clock, RepairJson json) throws Exception {
        HttpSessionCsrfTokenRepository tokens=new HttpSessionCsrfTokenRepository(); tokens.setHeaderName("X-CSRF-TOKEN");
        http.csrf(csrf->csrf.csrfTokenRepository(tokens).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health/**","/actuator/jobs","/api/v1/version","/api/v1/repairs/version").permitAll().anyRequest().authenticated())
            .requestCache(cache->cache.disable())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->write(res,401,"UNAUTHENTICATED","请先登录",json))
                .accessDeniedHandler((req,res,ex)->write(res,403,"FORBIDDEN","无此操作权限或 CSRF 校验失败",json)))
            .addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
                    UUID requestId;
                    try { requestId=UUID.fromString(req.getHeader("X-Request-ID")); } catch(Exception ex){requestId=UUID.randomUUID();}
                    req.setAttribute("repairRequestId",requestId); res.setHeader("X-Request-ID",requestId.toString());
                    Authentication auth=SecurityContextHolder.getContext().getAuthentication();
                    if(req.getRequestURI().startsWith("/api/v1/repairs")&&!req.getRequestURI().equals("/api/v1/repairs/version")&&auth!=null&&auth.getPrincipal() instanceof SessionPrincipal p) {
                        try {
                            if(!clock.instant().isBefore(p.authenticatedAt().plus(Duration.ofHours(8))))throw new RepairException(401,"UNAUTHENTICATED","会话已过期");
                            core.validateSession(p,requestId);
                        } catch(RepairException ex){ if(ex.status==401){if(req.getSession(false)!=null)req.getSession(false).invalidate();SecurityContextHolder.clearContext();} write(res,ex.status,ex.code,ex.getMessage(),json);return;}
                    }
                    chain.doFilter(req,res);
                }
            },AnonymousAuthenticationFilter.class);
        return http.build();
    }
    private static void write(HttpServletResponse response,int status,String code,String message,RepairJson json)throws IOException{
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.write(Map.of("error",Map.of("code",code,"message",message,"details",Map.of()),"requestId",UUID.randomUUID())));
    }
}
