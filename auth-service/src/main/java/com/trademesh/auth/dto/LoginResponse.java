package com.trademesh.auth.dto;

public record LoginResponse(String token, long expiresIn) {
}
