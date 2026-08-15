package com.trademesh.backend.service;

import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderType;

import java.math.BigDecimal;
import java.util.UUID;

/** Input to {@link TradeService#submitOrder(SubmitOrderRequest)}. {@code price} is null for MARKET orders. */
public record SubmitOrderRequest(UUID userId, String symbol, OrderSide side, OrderType type,
                                  BigDecimal price, BigDecimal quantity) {
}
