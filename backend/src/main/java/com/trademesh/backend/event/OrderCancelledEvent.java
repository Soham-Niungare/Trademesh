package com.trademesh.backend.event;

import com.trademesh.backend.engine.OrderBookSnapshot;

/**
 * Published by {@code TradeService} once per successful {@code cancelOrder}
 * call, strictly after that call's transaction has committed. Deliberately
 * separate from {@link TradeExecutedEvent} rather than reusing it with empty
 * trade fields: a cancellation never involves a trade, so forcing it through
 * the same event shape would leave trade-shaped fields permanently
 * meaningless for this case.
 */
public record OrderCancelledEvent(String symbol, OrderStateChange cancelledOrder, OrderBookSnapshot orderBookSnapshot) {
}
