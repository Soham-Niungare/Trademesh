package com.trademesh.backend.event;

import com.trademesh.backend.engine.EngineTrade;
import com.trademesh.backend.engine.OrderBookSnapshot;

import java.util.List;

/**
 * Published by {@code TradeService} once per {@code submitOrder} call, strictly
 * after that call's transaction has committed (never from inside the
 * {@code TransactionTemplate} callback -- see {@code TradeService}). Carries
 * everything downstream consumers ({@code MarketDataService},
 * {@code WebSocketPublisher}) need without re-deriving it themselves: every
 * trade generated (possibly none), the incoming order's own final state, every
 * resting order whose state changed too, and the resulting book snapshot for
 * the symbol.
 */
public record TradeExecutedEvent(String symbol, List<EngineTrade> trades, OrderStateChange incomingOrder,
                                  List<OrderStateChange> affectedOrders, OrderBookSnapshot orderBookSnapshot) {

    public TradeExecutedEvent {
        trades = List.copyOf(trades);
        affectedOrders = List.copyOf(affectedOrders);
    }
}
