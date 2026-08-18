package com.trademesh.backend.websocket;

import com.trademesh.backend.engine.PriceLevel;

import java.math.BigDecimal;

public record PriceLevelMessage(BigDecimal price, BigDecimal totalQuantity) {

    public static PriceLevelMessage from(PriceLevel level) {
        return new PriceLevelMessage(level.price(), level.totalQuantity());
    }
}
