package com.trademesh.backend.websocket;

import com.trademesh.backend.engine.EngineTrade;
import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.event.OrderCancelledEvent;
import com.trademesh.backend.event.OrderStateChange;
import com.trademesh.backend.event.TradeExecutedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes {@link TradeExecutedEvent}/{@link OrderCancelledEvent} -- published by
 * {@code TradeService} strictly after its transaction has committed -- to the
 * appropriate per-symbol STOMP topics. Scoped per symbol the same way
 * {@code MarketDataService}'s Redis keys are ({@code marketdata:orderbook:{symbol}}),
 * so a client watching one symbol isn't flooded with every other symbol's
 * activity:
 *
 * <ul>
 *   <li>{@code /topic/market/{symbol}/trades} -- TRADE_EXECUTED, one message per trade</li>
 *   <li>{@code /topic/market/{symbol}/orders} -- ORDER_UPDATED, one message per order whose state changed</li>
 *   <li>{@code /topic/market/{symbol}/orderbook} -- ORDER_BOOK_UPDATED, the resulting snapshot</li>
 * </ul>
 *
 * A broadcast failure here is caught and logged, never rethrown -- same
 * principle as {@code MarketDataService}'s Redis writes: the trade/cancellation
 * this reacts to is already durably committed by the time this runs, so a
 * WebSocket outage must not turn that success into an error response for the
 * caller that triggered it.
 */
@Component
public class WebSocketPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onTradeExecuted(TradeExecutedEvent event) {
        try {
            for (EngineTrade trade : event.trades()) {
                messagingTemplate.convertAndSend(tradesTopic(event.symbol()), TradeExecutedMessage.from(trade));
            }

            publishOrderUpdated(event.symbol(), event.incomingOrder());
            for (OrderStateChange affected : event.affectedOrders()) {
                publishOrderUpdated(event.symbol(), affected);
            }

            publishOrderBookUpdated(event.orderBookSnapshot());
        } catch (MessagingException e) {
            log.warn("Failed to publish WebSocket update(s) for symbol {} -- trade/order persistence is unaffected",
                    event.symbol(), e);
        }
    }

    @EventListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        try {
            publishOrderUpdated(event.symbol(), event.cancelledOrder());
            publishOrderBookUpdated(event.orderBookSnapshot());
        } catch (MessagingException e) {
            log.warn("Failed to publish WebSocket update(s) for symbol {} -- trade/order persistence is unaffected",
                    event.symbol(), e);
        }
    }

    private void publishOrderUpdated(String symbol, OrderStateChange change) {
        messagingTemplate.convertAndSend(ordersTopic(symbol), OrderUpdatedMessage.from(change));
    }

    private void publishOrderBookUpdated(OrderBookSnapshot snapshot) {
        messagingTemplate.convertAndSend(orderBookTopic(snapshot.symbol()), OrderBookUpdatedMessage.from(snapshot));
    }

    private static String tradesTopic(String symbol) {
        return "/topic/market/" + symbol + "/trades";
    }

    private static String ordersTopic(String symbol) {
        return "/topic/market/" + symbol + "/orders";
    }

    private static String orderBookTopic(String symbol) {
        return "/topic/market/" + symbol + "/orderbook";
    }
}
