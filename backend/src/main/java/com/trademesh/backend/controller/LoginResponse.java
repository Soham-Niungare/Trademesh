package com.trademesh.backend.controller;

public record LoginResponse(String token, long expiresIn) {
}
