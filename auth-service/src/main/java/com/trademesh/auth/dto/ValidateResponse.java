package com.trademesh.auth.dto;

import java.util.UUID;

public record ValidateResponse(UUID userId, String username) {
}
