package com.trademesh.backend.websocket;

import com.trademesh.backend.engine.OrderBookSnapshot;

import java.util.List;

/** Published to {@code /topic/market/{symbol}/orderbook} on every change to that symbol's book. */
public record OrderBookUpdatedMessage(String symbol, List<PriceLevelMessage> bids, List<PriceLevelMessage> asks) {

    public static OrderBookUpdatedMessage from(OrderBookSnapshot snapshot) {
        return new OrderBookUpdatedMessage(snapshot.symbol(),
                snapshot.bids().stream().map(PriceLevelMessage::from).toList(),
                snapshot.asks().stream().map(PriceLevelMessage::from).toList());
    }
}
