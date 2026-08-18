package com.trademesh.backend.websocket;

import com.trademesh.backend.engine.EngineTrade;

import java.math.BigDecimal;
import java.util.UUID;

/** Published to {@code /topic/market/{symbol}/trades} -- one message per trade. */
public record TradeExecutedMessage(String symbol, UUID buyOrderId, UUID sellOrderId, BigDecimal price,
                                    BigDecimal quantity) {

    public static TradeExecutedMessage from(EngineTrade trade) {
        return new TradeExecutedMessage(trade.symbol(), trade.buyOrderId(), trade.sellOrderId(), trade.price(),
                trade.quantity());
    }
}
