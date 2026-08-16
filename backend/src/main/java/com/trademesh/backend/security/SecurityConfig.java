package com.trademesh.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
