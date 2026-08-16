package com.trademesh.backend.controller;

import com.trademesh.backend.engine.PriceLevel;

import java.math.BigDecimal;

public record PriceLevelResponse(BigDecimal price, BigDecimal totalQuantity) {

    public static PriceLevelResponse from(PriceLevel level) {
        return new PriceLevelResponse(level.price(), level.totalQuantity());
    }
}
