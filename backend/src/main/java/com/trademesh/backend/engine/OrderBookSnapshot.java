package com.trademesh.backend.engine;

import java.util.List;

/**
 * Point-in-time view of an {@link OrderBook}. {@code bids} are ordered highest
 * price first, {@code asks} lowest price first — the same order a matching
 * incoming order would consume them in.
 */
public record OrderBookSnapshot(String symbol, List<PriceLevel> bids, List<PriceLevel> asks) {

    public OrderBookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
