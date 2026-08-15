package com.trademesh.portfolio.dto;

import com.trademesh.portfolio.entity.Holding;

import java.math.BigDecimal;

public record HoldingResponse(String symbol, BigDecimal quantity, BigDecimal avgPrice) {

    public static HoldingResponse from(Holding holding) {
        return new HoldingResponse(holding.getSymbol(), holding.getQuantity(), holding.getAvgPrice());
    }
}
