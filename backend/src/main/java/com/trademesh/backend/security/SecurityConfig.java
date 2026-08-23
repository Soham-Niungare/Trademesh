package com.trademesh.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless, JWT-only security: no sessions, no CSRF (nothing cookie-based to
 * forge), no {@code httpBasic}/{@code formLogin} -- login is a plain
 * {@code /api/auth/login} REST call that hands back a token, not something Spring
 * Security itself brokers. See docs/architecture.md for the full request flow.
 *
 * {@link JwtAuthenticationFilter} is built here as a plain object rather than
 * exposed as its own {@code @Component}/{@code @Bean}: Spring Boot auto-registers
 * any {@code Filter}-type bean it finds in the context as a second, independent
 * servlet filter (via its own {@code FilterRegistrationBean} machinery) in
 * addition to wherever Spring Security's chain places it -- meaning it would run
 * twice per request, at an unrelated position in the servlet filter order. Not
 * making it a bean at all sidesteps that entirely.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // Picks up the CorsConfigurationSource bean (see CorsConfig), which
                // scopes cross-origin access to /api/**. This also places Spring
                // Security's CorsFilter ahead of the authorization rules, so a
                // preflight OPTIONS -- which carries no Authorization header and
                // would otherwise be rejected as unauthenticated -- is answered
                // before it can be challenged.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // /ws/** is the STOMP/SockJS handshake + transport-negotiation
                        // path (info, xhr-streaming, xhr, websocket, ...) -- SockJS's
                        // fallback transports genuinely need more than GET (POST for
                        // polling, OPTIONS for preflight), unlike /api/market/** below,
                        // so this isn't scoped to one HTTP method.
                        // /error is the container's ERROR-dispatch target for any
                        // exception no @ExceptionHandler claimed, and for the statuses
                        // Spring MVC sets without throwing to a controller at all (405,
                        // 415, 404). It must be permitted, not authenticated: that
                        // dispatch is a fresh request, and OncePerRequestFilter skips
                        // itself on it by default (shouldNotFilterErrorDispatch), so
                        // JwtAuthenticationFilter never runs and the request arrives
                        // unauthenticated no matter who the caller is. Securing it meant
                        // every such error -- including on permitAll routes like
                        // /api/auth/login -- came back 401 instead of its real status.
                        .requestMatchers("/api/auth/**", "/actuator/health", "/ws/**", "/error").permitAll()
                        // Market data is a read-only, price-level-aggregated view with no
                        // per-order/per-user detail -- public in a real exchange, and the
                        // dashboard (§14/Phase 7) is expected to show the book before login.
                        // Scoped to GET specifically so a future authenticated/admin write
                        // under /api/market/** (e.g. a manual-rebuild trigger) doesn't
                        // inherit this by accident -- it would fall through to the
                        // authenticated-by-default rule below instead.
                        .requestMatchers(HttpMethod.GET, "/api/market/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
