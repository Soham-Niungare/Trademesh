package com.trademesh.backend.engine;

import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    private static final String SYMBOL = "AAPL";

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook(SYMBOL);
    }

    private static EngineOrder limit(OrderSide side, String price, String quantity) {
        return new EngineOrder(UUID.randomUUID(), UUID.randomUUID(), SYMBOL, side, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(quantity));
    }

    private static EngineOrder market(OrderSide side, String quantity) {
        return new EngineOrder(UUID.randomUUID(), UUID.randomUUID(), SYMBOL, side, OrderType.MARKET,
                null, new BigDecimal(quantity));
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    @DisplayName("project-doc example: incoming buy partially fills against the nearer sell level and rests for the remainder")
    void docExample_partialFillRestsInBook() {
        EngineOrder sell1 = limit(OrderSide.SELL, "101", "100");
        EngineOrder sell2 = limit(OrderSide.SELL, "102", "200");
        book.submitOrder(sell1);
        book.submitOrder(sell2);

        EngineOrder buy = limit(OrderSide.BUY, "101", "150");
        MatchResult result = book.submitOrder(buy);

        assertEquals(1, result.trades().size());
        EngineTrade trade = result.trades().get(0);
        assertMoneyEquals("101", trade.price());
        assertMoneyEquals("100", trade.quantity());
        assertEquals(buy.getId(), trade.buyOrderId());
        assertEquals(sell1.getId(), trade.sellOrderId());

        assertEquals(OrderStatus.PARTIALLY_FILLED, result.finalStatus());
        assertMoneyEquals("50", result.remainingQuantity());
        assertEquals(OrderStatus.FILLED, sell1.getStatus());

        OrderBookSnapshot snapshot = book.getSnapshot();
        assertEquals(1, snapshot.bids().size());
        assertMoneyEquals("101", snapshot.bids().get(0).price());
        assertMoneyEquals("50", snapshot.bids().get(0).totalQuantity());
    }

    @Test
    @DisplayName("exact quantity match fully fills both sides with no remainder")
    void exactQuantityMatch_fillsBothOrdersCompletely() {
        EngineOrder sell = limit(OrderSide.SELL, "100", "50");
        book.submitOrder(sell);

        EngineOrder buy = limit(OrderSide.BUY, "100", "50");
        MatchResult result = book.submitOrder(buy);

        assertEquals(1, result.trades().size());
        assertMoneyEquals("50", result.trades().get(0).quantity());
        assertEquals(OrderStatus.FILLED, result.finalStatus());
        assertMoneyEquals("0", result.remainingQuantity());
        assertEquals(OrderStatus.FILLED, sell.getStatus());

        OrderBookSnapshot snapshot = book.getSnapshot();
        assertTrue(snapshot.bids().isEmpty());
        assertTrue(snapshot.asks().isEmpty());
    }

    @Test
    @DisplayName("price-time priority: two sells at the same price fill in arrival order")
    void sameSellPrice_olderOrderFillsFirst() {
        EngineOrder olderSell = limit(OrderSide.SELL, "100", "50");
        EngineOrder newerSell = limit(OrderSide.SELL, "100", "50");
        book.submitOrder(olderSell);
        book.submitOrder(newerSell);

        EngineOrder buy = limit(OrderSide.BUY, "100", "50");
        MatchResult result = book.submitOrder(buy);

        assertEquals(1, result.trades().size());
        assertEquals(olderSell.getId(), result.trades().get(0).sellOrderId());
        assertEquals(OrderStatus.FILLED, olderSell.getStatus());
        assertEquals(OrderStatus.OPEN, newerSell.getStatus());
    }

    @Test
    @DisplayName("price priority: incoming buy matches the lowest sell price first even though a higher sell also crosses")
    void buyMatchesLowestSellPriceFirst() {
        EngineOrder higherPricedSell = limit(OrderSide.SELL, "105", "50");
        EngineOrder lowerPricedSell = limit(OrderSide.SELL, "100", "50");
        book.submitOrder(higherPricedSell);
        book.submitOrder(lowerPricedSell);

        EngineOrder buy = limit(OrderSide.BUY, "110", "50");
        MatchResult result = book.submitOrder(buy);

        assertEquals(1, result.trades().size());
        assertMoneyEquals("100", result.trades().get(0).price());
        assertEquals(lowerPricedSell.getId(), result.trades().get(0).sellOrderId());
        assertEquals(OrderStatus.FILLED, lowerPricedSell.getStatus());
        assertEquals(OrderStatus.OPEN, higherPricedSell.getStatus());
    }

    @Test
    @DisplayName("incoming sell matches resting buys highest price first (symmetric matching path)")
    void sellMatchesHighestBuyPriceFirst() {
        EngineOrder higherBuy = limit(OrderSide.BUY, "105", "50");
        EngineOrder lowerBuy = limit(OrderSide.BUY, "100", "50");
        book.submitOrder(higherBuy);
        book.submitOrder(lowerBuy);

        EngineOrder sell = limit(OrderSide.SELL, "100", "80");
        MatchResult result = book.submitOrder(sell);

        assertEquals(2, result.trades().size());
        assertMoneyEquals("105", result.trades().get(0).price());
        assertEquals(higherBuy.getId(), result.trades().get(0).buyOrderId());
        assertMoneyEquals("100", result.trades().get(1).price());
        assertEquals(lowerBuy.getId(), result.trades().get(1).buyOrderId());

        assertEquals(OrderStatus.FILLED, result.finalStatus());
        assertMoneyEquals("0", result.remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, lowerBuy.getStatus());
        assertMoneyEquals("20", lowerBuy.getRemainingQuantity());
    }

    @Test
    @DisplayName("market buy with insufficient liquidity partially fills and does not rest in the book")
    void marketBuy_insufficientLiquidity_partiallyFillsAndDoesNotRest() {
        EngineOrder sell = limit(OrderSide.SELL, "100", "30");
        book.submitOrder(sell);

        EngineOrder marketBuy = market(OrderSide.BUY, "100");
        MatchResult result = book.submitOrder(marketBuy);

        assertEquals(1, result.trades().size());
        assertMoneyEquals("30", result.trades().get(0).quantity());
        assertMoneyEquals("70", result.remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, result.finalStatus());

        assertTrue(book.getSnapshot().bids().isEmpty(), "market order must never rest in the book");
        assertFalse(book.cancelOrder(marketBuy.getId()), "an order that never rested cannot be cancelled");
    }

    @Test
    @DisplayName("market buy with zero liquidity matches nothing")
    void marketBuy_zeroLiquidity_matchesNothing() {
        EngineOrder marketBuy = market(OrderSide.BUY, "50");
        MatchResult result = book.submitOrder(marketBuy);

        assertTrue(result.trades().isEmpty());
        assertMoneyEquals("50", result.remainingQuantity());
        assertEquals(OrderStatus.OPEN, result.finalStatus());
        assertTrue(book.getSnapshot().bids().isEmpty());
    }

    @Test
    @DisplayName("market sell sweeps through multiple buy price levels, highest price first")
    void marketSell_sweepsBuyLevels() {
        EngineOrder buy1 = limit(OrderSide.BUY, "105", "10");
        EngineOrder buy2 = limit(OrderSide.BUY, "104", "10");
        book.submitOrder(buy1);
        book.submitOrder(buy2);

        EngineOrder marketSell = market(OrderSide.SELL, "15");
        MatchResult result = book.submitOrder(marketSell);

        assertEquals(2, result.trades().size());
        assertMoneyEquals("105", result.trades().get(0).price());
        assertMoneyEquals("10", result.trades().get(0).quantity());
        assertMoneyEquals("104", result.trades().get(1).price());
        assertMoneyEquals("5", result.trades().get(1).quantity());

        assertEquals(OrderStatus.FILLED, result.finalStatus());
        assertMoneyEquals("0", result.remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy2.getStatus());
        assertMoneyEquals("5", buy2.getRemainingQuantity());
    }

    @Test
    @DisplayName("a large market buy sweeps through several sell price levels in order until filled")
    void marketBuy_sweepsMultipleSellLevels() {
        EngineOrder sell1 = limit(OrderSide.SELL, "100", "10");
        EngineOrder sell2 = limit(OrderSide.SELL, "101", "10");
        EngineOrder sell3 = limit(OrderSide.SELL, "102", "10");
        book.submitOrder(sell1);
        book.submitOrder(sell2);
        book.submitOrder(sell3);

        EngineOrder marketBuy = market(OrderSide.BUY, "25");
        MatchResult result = book.submitOrder(marketBuy);

        assertEquals(3, result.trades().size());
        List<EngineTrade> trades = result.trades();
        assertMoneyEquals("100", trades.get(0).price());
        assertMoneyEquals("10", trades.get(0).quantity());
        assertMoneyEquals("101", trades.get(1).price());
        assertMoneyEquals("10", trades.get(1).quantity());
        assertMoneyEquals("102", trades.get(2).price());
        assertMoneyEquals("5", trades.get(2).quantity());

        assertEquals(OrderStatus.FILLED, result.finalStatus());
        assertMoneyEquals("0", result.remainingQuantity());

        OrderBookSnapshot snapshot = book.getSnapshot();
        assertEquals(1, snapshot.asks().size());
        assertMoneyEquals("102", snapshot.asks().get(0).price());
        assertMoneyEquals("5", snapshot.asks().get(0).totalQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, sell3.getStatus());
    }

    @Test
    @DisplayName("cancelling a resting order removes it so it can no longer match")
    void cancelOrder_removesRestingOrderFromBook() {
        EngineOrder sell = limit(OrderSide.SELL, "100", "50");
        book.submitOrder(sell);

        assertTrue(book.cancelOrder(sell.getId()));
        assertEquals(OrderStatus.CANCELLED, sell.getStatus());
        assertTrue(book.getSnapshot().asks().isEmpty());

        EngineOrder buy = limit(OrderSide.BUY, "100", "50");
        MatchResult result = book.submitOrder(buy);

        assertTrue(result.trades().isEmpty());
        assertEquals(OrderStatus.OPEN, result.finalStatus());
        assertMoneyEquals("50", result.remainingQuantity());
    }

    @Test
    @DisplayName("cancelling an unknown order id is a no-op that returns false")
    void cancelOrder_unknownId_returnsFalse() {
        assertFalse(book.cancelOrder(UUID.randomUUID()));
    }

    @Test
    @DisplayName("cancelling an already-filled order returns false")
    void cancelOrder_alreadyFilledOrder_returnsFalse() {
        EngineOrder sell = limit(OrderSide.SELL, "100", "50");
        book.submitOrder(sell);

        EngineOrder buy = limit(OrderSide.BUY, "100", "50");
        book.submitOrder(buy);

        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertFalse(book.cancelOrder(sell.getId()));
    }

    @Test
    @DisplayName("cancelling an already-cancelled order returns false")
    void cancelOrder_alreadyCancelledOrder_returnsFalse() {
        EngineOrder sell = limit(OrderSide.SELL, "100", "50");
        book.submitOrder(sell);

        assertTrue(book.cancelOrder(sell.getId()));
        assertFalse(book.cancelOrder(sell.getId()));
    }
}
