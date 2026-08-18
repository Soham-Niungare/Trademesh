package com.trademesh.backend.event;

import com.trademesh.backend.engine.EngineOrder;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** No Spring context, no database -- both OrderStateChange.from overloads are pure field mapping. */
class OrderStateChangeTest {

    @Test
    void from_engineOrder_mapsAllFields() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        EngineOrder order = new EngineOrder(orderId, userId, "AAPL", OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("4"), OrderStatus.PARTIALLY_FILLED);

        OrderStateChange change = OrderStateChange.from(order);

        assertEquals(orderId, change.orderId());
        assertEquals(userId, change.userId());
        assertEquals("AAPL", change.symbol());
        assertEquals(OrderStatus.PARTIALLY_FILLED, change.status());
        assertEquals(0, new BigDecimal("4").compareTo(change.remainingQuantity()));
    }

    @Test
    void from_jpaOrder_mapsAllFields() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSymbol("MSFT");
        order.setSide(OrderSide.SELL);
        order.setType(OrderType.LIMIT);
        order.setPrice(new BigDecimal("200"));
        order.setQuantity(new BigDecimal("5"));
        order.setRemainingQuantity(new BigDecimal("5"));
        order.setStatus(OrderStatus.CANCELLED);
        order.setCreatedAt(Instant.now());

        OrderStateChange change = OrderStateChange.from(order);

        assertEquals(orderId, change.orderId());
        assertEquals(userId, change.userId());
        assertEquals("MSFT", change.symbol());
        assertEquals(OrderStatus.CANCELLED, change.status());
        assertEquals(0, new BigDecimal("5").compareTo(change.remainingQuantity()));
    }
}
