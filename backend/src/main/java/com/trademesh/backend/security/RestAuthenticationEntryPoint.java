package com.trademesh.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.backend.web.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Without this, Spring Security's default entry point for a request with no
 * {@code httpBasic}/{@code formLogin} configured is {@code Http403ForbiddenEntryPoint}
 * -- a request with a missing/invalid token would silently come back 403, not 401.
 * This is the minimal fix for that (correct status, no hand-rolled response logic),
 * writing the same {@link ErrorResponse} shape {@code GlobalExceptionHandler} uses so
 * a caller sees one consistent error body regardless of whether the rejection
 * happened in the security filter chain or inside a controller.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(HttpStatus.UNAUTHORIZED, "Missing or invalid authentication token");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
