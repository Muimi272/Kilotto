package club.muimi.kilotto.auth.security.config;

import club.muimi.kilotto.auth.security.AccountAccess;
import club.muimi.kilotto.auth.security.RequestGuardFilter;
import club.muimi.kilotto.auth.security.RequestLimits;
import club.muimi.kilotto.auth.security.RequestRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import club.muimi.kilotto.auth.security.ActiveUserFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import java.util.List;

@Configuration
@EnableConfigurationProperties(RequestLimits.class)
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(tokens)));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccountAccess access, SecurityContextRepository contexts,
                                    CsrfTokenRepository tokens, SessionAuthenticationStrategy sessions,
                                    RequestRateLimiter limiter, RequestLimits limits) {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(tokens))
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionAuthenticationStrategy(sessions))
                .addFilterBefore(new RequestGuardFilter(limiter, limits), SecurityContextHolderFilter.class)
                .addFilterAfter(new ActiveUserFilter(access), SecurityContextHolderFilter.class)
                .headers(headers -> headers
                        .contentSecurityPolicy(policy -> policy.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                        .referrerPolicy(policy -> policy.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/theme.js", "/opening.js", "/style.css",
                                "/fonts/noto-sans-sc/**",
                                "/join", "/login", "/rooms", "/rooms/new", "/admin/users", "/admin/users/new",
                                "/room/*", "/room/*/history",
                                "/favicon.svg", "/health", "/api/auth/csrf", "/ws/rooms/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/me", "/api/rooms", "/api/rooms/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"status\":401,\"detail\":\"请先登录\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"status\":403,\"detail\":\"权限不足或 CSRF 令牌无效\"}");
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("KILOTTO_SESSION")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }
}
