package com.trademesh.backend.websocket;

import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.event.OrderStateChange;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published to {@code /topic/market/{symbol}/orders} -- one message per order
 * whose state changed (matched, partially filled, filled, or cancelled).
 * Deliberately omits {@code userId}, unlike the internal {@link OrderStateChange}
 * it's built from: this topic is public (see {@code WebSocketConfig}/{@code SecurityConfig}),
 * and broadcasting who placed an order would leak per-user information this
 * project has otherwise been careful never to expose outside of ownership
 * checks (see docs/architecture.md's "Order ownership" section). The order's
 * id, symbol, status, and remaining quantity reveal nothing beyond what's
 * already inferable from the equally-public order-book snapshot.
 */
public record OrderUpdatedMessage(UUID orderId, String symbol, OrderStatus status, BigDecimal remainingQuantity) {

    public static OrderUpdatedMessage from(OrderStateChange change) {
        return new OrderUpdatedMessage(change.orderId(), change.symbol(), change.status(), change.remainingQuantity());
    }
}
