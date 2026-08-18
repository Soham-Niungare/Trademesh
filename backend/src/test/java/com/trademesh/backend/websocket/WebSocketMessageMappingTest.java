package com.trademesh.backend.websocket;

import com.trademesh.backend.engine.EngineTrade;
import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.engine.PriceLevel;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.event.OrderStateChange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** No Spring context -- each *Message.from(...) is a pure mapping function. */
class WebSocketMessageMappingTest {

    @Test
    void tradeExecutedMessage_mapsAllFieldsFromEngineTrade() {
        UUID buyOrderId = UUID.randomUUID();
        UUID sellOrderId = UUID.randomUUID();
        EngineTrade trade = new EngineTrade("AAPL", buyOrderId, sellOrderId, new BigDecimal("101"), new BigDecimal("7"));

        TradeExecutedMessage message = TradeExecutedMessage.from(trade);

        assertEquals("AAPL", message.symbol());
        assertEquals(buyOrderId, message.buyOrderId());
        assertEquals(sellOrderId, message.sellOrderId());
        assertEquals(0, new BigDecimal("101").compareTo(message.price()));
        assertEquals(0, new BigDecimal("7").compareTo(message.quantity()));
    }

    /**
     * OrderStateChange carries userId (useful internally -- see MarketDataService
     * /future per-user work), but OrderUpdatedMessage is broadcast on a public
     * topic and must not leak it -- see its class javadoc for why. The absence
     * of a userId component is a structural, compile-time property of the
     * record itself (there is no message.userId() to call); this test is the
     * remaining runtime check, that mapping the other four fields is correct.
     */
    @Test
    void orderUpdatedMessage_mapsFieldsOtherThanUserId() {
        OrderStateChange change = new OrderStateChange(UUID.randomUUID(), UUID.randomUUID(), "AAPL",
                OrderStatus.FILLED, BigDecimal.ZERO);

        OrderUpdatedMessage message = OrderUpdatedMessage.from(change);

        assertEquals(change.orderId(), message.orderId());
        assertEquals("AAPL", message.symbol());
        assertEquals(OrderStatus.FILLED, message.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(message.remainingQuantity()));
    }

    @Test
    void priceLevelMessage_mapsPriceAndQuantity() {
        PriceLevel level = new PriceLevel(new BigDecimal("99.5"), new BigDecimal("42"));

        PriceLevelMessage message = PriceLevelMessage.from(level);

        assertEquals(0, new BigDecimal("99.5").compareTo(message.price()));
        assertEquals(0, new BigDecimal("42").compareTo(message.totalQuantity()));
    }

    @Test
    void orderBookUpdatedMessage_mapsSymbolAndBothSidesInOrder() {
        OrderBookSnapshot snapshot = new OrderBookSnapshot("AAPL",
                List.of(new PriceLevel(new BigDecimal("99"), new BigDecimal("10"))),
                List.of(new PriceLevel(new BigDecimal("101"), new BigDecimal("5")),
                        new PriceLevel(new BigDecimal("102"), new BigDecimal("3"))));

        OrderBookUpdatedMessage message = OrderBookUpdatedMessage.from(snapshot);

        assertEquals("AAPL", message.symbol());
        assertEquals(1, message.bids().size());
        assertEquals(0, new BigDecimal("99").compareTo(message.bids().get(0).price()));
        assertEquals(2, message.asks().size());
        assertEquals(0, new BigDecimal("101").compareTo(message.asks().get(0).price()));
        assertEquals(0, new BigDecimal("102").compareTo(message.asks().get(1).price()));
    }
}
