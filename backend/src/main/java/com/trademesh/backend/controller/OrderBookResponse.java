package com.trademesh.backend.controller;

import com.trademesh.backend.engine.OrderBookSnapshot;

import java.util.List;

/** {@code bestBid}/{@code bestAsk} are null when that side is empty, not omitted. */
public record OrderBookResponse(String symbol, PriceLevelResponse bestBid, PriceLevelResponse bestAsk,
                                 List<PriceLevelResponse> bids, List<PriceLevelResponse> asks) {

    public static OrderBookResponse from(OrderBookSnapshot snapshot) {
        return new OrderBookResponse(
                snapshot.symbol(),
                snapshot.bids().isEmpty() ? null : PriceLevelResponse.from(snapshot.bids().get(0)),
                snapshot.asks().isEmpty() ? null : PriceLevelResponse.from(snapshot.asks().get(0)),
                snapshot.bids().stream().map(PriceLevelResponse::from).toList(),
                snapshot.asks().stream().map(PriceLevelResponse::from).toList());
    }
}
