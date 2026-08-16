package com.trademesh.backend.web;

import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * The one JSON error shape used everywhere a request fails -- both from
 * {@link GlobalExceptionHandler} (exceptions thrown during controller execution)
 * and from {@code RestAuthenticationEntryPoint} (401s rejected by the Spring
 * Security filter chain before a controller is ever reached, which
 * {@code @RestControllerAdvice} can't intercept).
 */
public record ErrorResponse(Instant timestamp, int status, String error, String message) {

    public static ErrorResponse of(HttpStatus status, String message) {
        return new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message);
    }
}
