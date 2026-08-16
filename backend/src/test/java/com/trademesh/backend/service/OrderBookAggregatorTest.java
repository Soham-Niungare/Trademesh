package com.trademesh.backend.service;

import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No Spring context, no database -- OrderBookAggregator is a pure function over a List<Order>. */
class OrderBookAggregatorTest {

    private static Order restingOrder(String symbol, OrderSide side, OrderType type, String price, String remainingQuantity) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setSymbol(symbol);
        order.setSide(side);
        order.setType(type);
        order.setPrice(price == null ? null : new BigDecimal(price));
        order.setQuantity(new BigDecimal(remainingQuantity));
        order.setRemainingQuantity(new BigDecimal(remainingQuantity));
        order.setStatus(OrderStatus.OPEN);
        order.setCreatedAt(Instant.now());
        return order;
    }

    private static Order limitOrder(String symbol, OrderSide side, String price, String remainingQuantity) {
        return restingOrder(symbol, side, OrderType.LIMIT, price, remainingQuantity);
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void aggregate_sumsQuantityAtTheSamePriceLevel() {
        String symbol = "AAPL";
        List<Order> orders = List.of(
                limitOrder(symbol, OrderSide.SELL, "100", "30"),
                limitOrder(symbol, OrderSide.SELL, "100", "20"));

        OrderBookSnapshot snapshot = OrderBookAggregator.aggregate(symbol, orders);

        assertEquals(1, snapshot.asks().size());
        assertMoneyEquals("100", snapshot.asks().get(0).price());
        assertMoneyEquals("50", snapshot.asks().get(0).totalQuantity());
        assertTrue(snapshot.bids().isEmpty());
    }

    @Test
    void aggregate_sortsBidsDescendingAndAsksAscending() {
        String symbol = "AAPL";
        List<Order> orders = List.of(
                limitOrder(symbol, OrderSide.BUY, "95", "1"),
                limitOrder(symbol, OrderSide.BUY, "99", "1"),
                limitOrder(symbol, OrderSide.BUY, "97", "1"),
                limitOrder(symbol, OrderSide.SELL, "105", "1"),
                limitOrder(symbol, OrderSide.SELL, "101", "1"),
                limitOrder(symbol, OrderSide.SELL, "103", "1"));

        OrderBookSnapshot snapshot = OrderBookAggregator.aggregate(symbol, orders);

        assertEquals(List.of("99", "97", "95"),
                snapshot.bids().stream().map(level -> level.price().toPlainString()).toList());
        assertEquals(List.of("101", "103", "105"),
                snapshot.asks().stream().map(level -> level.price().toPlainString()).toList());
    }

    @Test
    void aggregate_ignoresOrdersForOtherSymbols() {
        List<Order> orders = List.of(
                limitOrder("AAPL", OrderSide.BUY, "100", "5"),
                limitOrder("MSFT", OrderSide.BUY, "200", "9"));

        OrderBookSnapshot snapshot = OrderBookAggregator.aggregate("AAPL", orders);

        assertEquals(1, snapshot.bids().size());
        assertMoneyEquals("100", snapshot.bids().get(0).price());
        assertMoneyEquals("5", snapshot.bids().get(0).totalQuantity());
    }

    @Test
    void aggregate_ignoresMarketOrdersEvenIfPresent() {
        // A MARKET order carries a null price -- if this weren't filtered out, it
        // would NPE (a TreeMap can't take a null key), not just produce a wrong
        // number. Defense in depth against the exact class of bug found in
        // OrderBookWarmupRunner during Phase 5 (see docs/phases/phase-05).
        String symbol = "AAPL";
        List<Order> orders = List.of(
                limitOrder(symbol, OrderSide.BUY, "100", "5"),
                restingOrder(symbol, OrderSide.BUY, OrderType.MARKET, null, "8"));

        OrderBookSnapshot snapshot = OrderBookAggregator.aggregate(symbol, orders);

        assertEquals(1, snapshot.bids().size());
        assertMoneyEquals("5", snapshot.bids().get(0).totalQuantity());
    }

    @Test
    void aggregate_emptyList_producesEmptySnapshot() {
        OrderBookSnapshot snapshot = OrderBookAggregator.aggregate("AAPL", List.of());

        assertTrue(snapshot.bids().isEmpty());
        assertTrue(snapshot.asks().isEmpty());
    }
}
