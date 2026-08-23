package com.trademesh.backend.engine;

import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EngineOrder's own invariant, tested where it is actually enforced. It was
 * previously only exercised incidentally, through an HTTP request that happened to
 * reach it -- which proved the rule holds for one caller but not that the constructor
 * itself refuses to build an invalid order.
 */
class EngineOrderTest {

    private static final String SYMBOL = "AAPL";

    @Test
    void limitOrderWithNullPrice_isRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new EngineOrder(UUID.randomUUID(), UUID.randomUUID(), SYMBOL, OrderSide.BUY,
                        OrderType.LIMIT, null, new BigDecimal("10")));

        assertEquals("price is required for LIMIT orders", thrown.getMessage());
    }

    /**
     * The rehydration constructor is a separate public entry point, used when
     * rebuilding a book from DB rows -- it must enforce the same invariant, not
     * trust that whatever produced the row already did.
     */
    @Test
    void limitOrderWithNullPrice_isRejectedByTheRehydrationConstructorToo() {
        assertThrows(IllegalArgumentException.class,
                () -> new EngineOrder(UUID.randomUUID(), UUID.randomUUID(), SYMBOL, OrderSide.SELL,
                        OrderType.LIMIT, null, new BigDecimal("10"), new BigDecimal("4"),
                        OrderStatus.PARTIALLY_FILLED));
    }

    /** A MARKET order carries no price by design; the guard must not catch it. */
    @Test
    void marketOrderWithNullPrice_isAccepted() {
        assertDoesNotThrow(() -> new EngineOrder(UUID.randomUUID(), UUID.randomUUID(), SYMBOL, OrderSide.BUY,
                OrderType.MARKET, null, new BigDecimal("10")));
    }

    @Test
    void freshOrder_defaultsToFullRemainingQuantityAndOpenStatus() {
        EngineOrder order = new EngineOrder(UUID.randomUUID(), UUID.randomUUID(), SYMBOL, OrderSide.BUY,
                OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("10"));

        assertEquals(0, new BigDecimal("10").compareTo(order.getRemainingQuantity()));
        assertEquals(OrderStatus.OPEN, order.getStatus());
    }
}
