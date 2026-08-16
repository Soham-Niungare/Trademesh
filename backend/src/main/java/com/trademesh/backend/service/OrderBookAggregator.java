package com.trademesh.backend.service;

import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.engine.PriceLevel;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderType;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Aggregates persisted orders into the same {price, totalQuantity}-per-level shape
 * {@code OrderBook.getSnapshot()} produces -- but computed directly from Postgres
 * rows, with no dependency on the in-memory engine, Spring, or Redis. Used by
 * {@link MarketDataService} wherever a snapshot needs to be rebuilt from the
 * durable source of truth rather than read from the live book.
 *
 * Deliberately self-contained rather than trusting the caller to have pre-filtered
 * the list: only {@code symbol}'s own {@code LIMIT} orders are ever aggregated,
 * everything else is ignored. A MARKET order slipping in here (e.g. a future
 * caller forgetting to filter by type) would otherwise NPE -- MARKET orders carry
 * a null price, and null can't be a TreeMap key.
 */
final class OrderBookAggregator {

    private OrderBookAggregator() {
    }

    static OrderBookSnapshot aggregate(String symbol, List<Order> orders) {
        return new OrderBookSnapshot(symbol,
                aggregateSide(symbol, orders, OrderSide.BUY, Comparator.reverseOrder()),
                aggregateSide(symbol, orders, OrderSide.SELL, Comparator.naturalOrder()));
    }

    private static List<PriceLevel> aggregateSide(String symbol, List<Order> orders, OrderSide side,
                                                    Comparator<BigDecimal> priceOrder) {
        TreeMap<BigDecimal, BigDecimal> totalsByPrice = new TreeMap<>(priceOrder);
        for (Order order : orders) {
            if (order.getType() != OrderType.LIMIT || order.getSide() != side || !symbol.equals(order.getSymbol())) {
                continue;
            }
            totalsByPrice.merge(order.getPrice(), order.getRemainingQuantity(), BigDecimal::add);
        }
        return totalsByPrice.entrySet().stream()
                .map(entry -> new PriceLevel(entry.getKey(), entry.getValue()))
                .toList();
    }
}
