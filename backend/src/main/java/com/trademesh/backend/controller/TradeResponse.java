package com.trademesh.backend.controller;

import com.trademesh.backend.entity.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeResponse(UUID id, String symbol, UUID buyOrderId, UUID sellOrderId,
                             BigDecimal price, BigDecimal quantity, Instant executedAt) {

    public static TradeResponse from(Trade trade) {
        return new TradeResponse(trade.getId(), trade.getSymbol(), trade.getBuyOrderId(), trade.getSellOrderId(),
                trade.getPrice(), trade.getQuantity(), trade.getExecutedAt());
    }
}
