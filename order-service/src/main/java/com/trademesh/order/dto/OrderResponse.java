package com.trademesh.order.dto;

import com.trademesh.order.entity.Order;
import com.trademesh.order.entity.OrderSide;
import com.trademesh.order.entity.OrderStatus;
import com.trademesh.order.entity.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        UUID userId,
        String symbol,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal price,
        OrderType type,
        OrderStatus status,
        BigDecimal filledQuantity,
        BigDecimal fillPrice,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getSymbol(),
                order.getSide(),
                order.getQuantity(),
                order.getPrice(),
                order.getType(),
                order.getStatus(),
                order.getFilledQuantity(),
                order.getFillPrice(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
