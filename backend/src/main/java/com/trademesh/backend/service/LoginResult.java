package com.trademesh.backend.service;

/** Result of {@link AuthService#login(String, String)}. */
public record LoginResult(String token, long expiresIn) {
}
