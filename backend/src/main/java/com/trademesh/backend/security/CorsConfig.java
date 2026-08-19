package com.trademesh.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Cross-origin access for the REST API, so a browser-based frontend served from
 * a different origin (the Next.js dev server on {@code http://localhost:3000})
 * can call it. Picked up by {@link SecurityConfig}'s {@code .cors(...)}, which
 * looks up a {@code CorsConfigurationSource} bean by type.
 *
 * <p>Covers {@code /api/**} and {@code /ws/**}, for different reasons. The API
 * entry is the point of this class. The WebSocket entry exists only to preserve
 * behaviour that already worked: enabling {@code .cors(...)} places Spring
 * Security's {@code CorsFilter} ahead of the whole chain, and that filter
 * <em>rejects</em> a preflight whose path has no configuration rather than
 * passing it along ({@code DefaultCorsProcessor} answers a null config with
 * 403 "Invalid CORS request"). Since Spring's SockJS service used to answer
 * {@code OPTIONS /ws/**} itself with 200, leaving {@code /ws/**} unmapped here
 * would have turned those preflights into 403s -- verified by measuring both
 * states directly. Today's sockjs-client happens not to send them (its
 * transports are CORS "simple requests"), so nothing visibly broke, but that is
 * a property of the client, not a guarantee of the protocol.
 *
 * <p>The {@code /ws/**} entry therefore mirrors the policy {@code WebSocketConfig}
 * already grants on that endpoint -- any origin, credentials allowed -- rather
 * than imposing the API's stricter allowlist on it. It restores the previous
 * answer to a preflight; it does not introduce a second, competing policy for
 * the handshake itself, which SockJS still owns.
 *
 * <p>Origins are configurable rather than hardcoded ({@code cors.allowed-origins}
 * in application.yml, overridable via the {@code CORS_ALLOWED_ORIGINS} env var,
 * same pattern as {@code JWT_SECRET}) so a deployed frontend origin can be
 * supplied at run time without a rebuild.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins}") List<String> allowedOrigins) {

        CorsConfiguration configuration = new CorsConfiguration();
        // Exact origins, not setAllowedOriginPatterns: the origins that may call
        // this API are known and enumerable. Patterns exist for wildcard
        // subdomains (e.g. per-PR preview deployments) and can be swapped in if
        // that need ever arises -- until then the stricter check is the right
        // default.
        configuration.setAllowedOrigins(allowedOrigins);
        // Exactly the methods the API exposes (see AuthController, OrderController,
        // MarketController). OPTIONS is listed for the preflight itself.
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        // Authorization carries the JWT; Content-Type is needed because the JSON
        // request bodies push POSTs past the "simple request" exemption.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Deliberately NOT setAllowCredentials(true). Authentication here is a
        // Bearer header, never a cookie (see SecurityConfig -- CSRF is disabled
        // precisely because nothing is cookie-based), so the browser has no
        // credentials to attach. Enabling it would widen exposure for no gain and
        // would additionally forbid a "*" origin if one were ever configured.
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/ws/**", webSocketPassThrough());
        return source;
    }

    /**
     * Reproduces what {@code WebSocketConfig}'s {@code setAllowedOriginPatterns("*")}
     * endpoint already answers, so the CorsFilter added by {@code .cors(...)} hands
     * SockJS preflights the same response they got before CORS was configured
     * instead of rejecting them. Deliberately NOT tied to {@code cors.allowed-origins}:
     * narrowing the WebSocket's origin policy would be a real behavioural change to
     * a public, deliberately-unauthenticated endpoint, and that decision belongs
     * with the endpoint in {@code WebSocketConfig}, not here.
     */
    private static CorsConfiguration webSocketPassThrough() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Patterns rather than setAllowedOrigins("*"): credentials and a literal
        // "*" origin are mutually exclusive, and SockJS's own responses set
        // Access-Control-Allow-Credentials: true.
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        return configuration;
    }
}
