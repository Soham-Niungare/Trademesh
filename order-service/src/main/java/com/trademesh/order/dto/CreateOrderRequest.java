package com.trademesh.order.dto;

import com.trademesh.order.entity.OrderSide;
import com.trademesh.order.entity.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID userId,
        @NotNull String symbol,
        @NotNull OrderSide side,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001") BigDecimal price,
        @NotNull OrderType type
) {
}
