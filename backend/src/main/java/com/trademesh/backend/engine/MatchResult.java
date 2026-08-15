package com.trademesh.backend.engine;

import com.trademesh.backend.entity.OrderStatus;

import java.math.BigDecimal;
import java.util.List;

/** Outcome of submitting one order to an {@link OrderBook}. */
public record MatchResult(List<EngineTrade> trades, OrderStatus finalStatus, BigDecimal remainingQuantity) {

    public MatchResult {
        trades = List.copyOf(trades);
    }
}
