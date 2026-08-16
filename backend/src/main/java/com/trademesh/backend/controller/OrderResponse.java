package com.trademesh.backend.controller;

import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(UUID id, UUID userId, String symbol, OrderSide side, OrderType type,
                             BigDecimal price, BigDecimal quantity, BigDecimal remainingQuantity,
                             OrderStatus status, Instant createdAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getUserId(), order.getSymbol(), order.getSide(),
                order.getType(), order.getPrice(), order.getQuantity(), order.getRemainingQuantity(),
                order.getStatus(), order.getCreatedAt());
    }
}
