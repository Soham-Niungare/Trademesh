package com.trademesh.backend.event;

import com.trademesh.backend.engine.EngineOrder;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An immutable, frozen-in-time snapshot of one order's state at the moment it
 * changed -- deliberately not the live, mutable {@link EngineOrder} itself
 * (which may still be resting in an {@code OrderBook} and could be mutated
 * again by a later match), so anything holding onto this can't be
 * retroactively surprised by a state change it didn't itself observe.
 */
public record OrderStateChange(UUID orderId, UUID userId, String symbol, OrderStatus status,
                                BigDecimal remainingQuantity) {

    public static OrderStateChange from(EngineOrder order) {
        return new OrderStateChange(order.getId(), order.getUserId(), order.getSymbol(), order.getStatus(),
                order.getRemainingQuantity());
    }

    public static OrderStateChange from(Order order) {
        return new OrderStateChange(order.getId(), order.getUserId(), order.getSymbol(), order.getStatus(),
                order.getRemainingQuantity());
    }
}
