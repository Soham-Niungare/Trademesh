package com.trademesh.backend.engine;

import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A single-symbol limit order book with price-time priority matching. Not
 * thread-safe — callers are responsible for synchronizing access if needed;
 * that belongs to whatever wires this into a concurrent context in a later
 * phase.
 */
public class OrderBook {

    private final String symbol;

    // Highest price first: the best bid an incoming sell can hit.
    private final TreeMap<BigDecimal, Deque<EngineOrder>> buyLevels = new TreeMap<>(Comparator.reverseOrder());
    // Lowest price first: the best ask an incoming buy can hit.
    private final TreeMap<BigDecimal, Deque<EngineOrder>> sellLevels = new TreeMap<>();

    private final Map<UUID, EngineOrder> restingOrders = new HashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    public MatchResult submitOrder(EngineOrder incoming) {
        if (!symbol.equals(incoming.getSymbol())) {
            throw new IllegalArgumentException(
                    "Order symbol '%s' does not belong to book '%s'".formatted(incoming.getSymbol(), symbol));
        }

        TreeMap<BigDecimal, Deque<EngineOrder>> oppositeLevels =
                incoming.getSide() == OrderSide.BUY ? sellLevels : buyLevels;
        List<EngineTrade> trades = match(incoming, oppositeLevels);

        finalizeStatus(incoming);

        if (incoming.getType() == OrderType.LIMIT && incoming.getRemainingQuantity().signum() > 0) {
            rest(incoming);
        }

        return new MatchResult(trades, incoming.getStatus(), incoming.getRemainingQuantity());
    }

    public boolean cancelOrder(UUID orderId) {
        EngineOrder order = restingOrders.remove(orderId);
        if (order == null) {
            return false;
        }

        TreeMap<BigDecimal, Deque<EngineOrder>> levels = order.getSide() == OrderSide.BUY ? buyLevels : sellLevels;
        Deque<EngineOrder> queue = levels.get(order.getPrice());
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                levels.remove(order.getPrice());
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        return true;
    }

    public OrderBookSnapshot getSnapshot() {
        return new OrderBookSnapshot(symbol, toPriceLevels(buyLevels), toPriceLevels(sellLevels));
    }

    /**
     * Matches {@code incoming} against {@code restingLevels} (the opposite side's
     * book, already ordered best-price-first) until it's fully filled, no longer
     * crosses the best remaining price, or the book is exhausted. Trade price is
     * always the resting order's price, never the incoming order's.
     */
    private List<EngineTrade> match(EngineOrder incoming, TreeMap<BigDecimal, Deque<EngineOrder>> restingLevels) {
        List<EngineTrade> trades = new ArrayList<>();

        while (incoming.getRemainingQuantity().signum() > 0 && !restingLevels.isEmpty()) {
            Map.Entry<BigDecimal, Deque<EngineOrder>> bestLevel = restingLevels.firstEntry();
            BigDecimal levelPrice = bestLevel.getKey();

            if (incoming.getType() == OrderType.LIMIT && !crosses(incoming, levelPrice)) {
                break;
            }

            Deque<EngineOrder> queue = bestLevel.getValue();
            while (incoming.getRemainingQuantity().signum() > 0 && !queue.isEmpty()) {
                EngineOrder resting = queue.peekFirst();
                BigDecimal tradeQuantity = incoming.getRemainingQuantity().min(resting.getRemainingQuantity());
                BigDecimal tradePrice = resting.getPrice();

                trades.add(incoming.getSide() == OrderSide.BUY
                        ? new EngineTrade(symbol, incoming.getId(), resting.getId(), tradePrice, tradeQuantity)
                        : new EngineTrade(symbol, resting.getId(), incoming.getId(), tradePrice, tradeQuantity));

                incoming.reduceRemainingQuantity(tradeQuantity);
                resting.reduceRemainingQuantity(tradeQuantity);

                if (resting.getRemainingQuantity().signum() == 0) {
                    resting.setStatus(OrderStatus.FILLED);
                    queue.pollFirst();
                    restingOrders.remove(resting.getId());
                } else {
                    resting.setStatus(OrderStatus.PARTIALLY_FILLED);
                }
            }

            if (queue.isEmpty()) {
                restingLevels.remove(levelPrice);
            }
        }

        return trades;
    }

    private boolean crosses(EngineOrder incoming, BigDecimal restingPrice) {
        return incoming.getSide() == OrderSide.BUY
                ? incoming.getPrice().compareTo(restingPrice) >= 0
                : incoming.getPrice().compareTo(restingPrice) <= 0;
    }

    private void rest(EngineOrder order) {
        TreeMap<BigDecimal, Deque<EngineOrder>> levels = order.getSide() == OrderSide.BUY ? buyLevels : sellLevels;
        levels.computeIfAbsent(order.getPrice(), price -> new ArrayDeque<>()).addLast(order);
        restingOrders.put(order.getId(), order);
    }

    /**
     * Market orders never rest, so a market order that doesn't fully fill has no
     * CANCELLED/OPEN-in-book state to report; OPEN here means "nothing filled",
     * not "resting". Callers should read {@link MatchResult#remainingQuantity()}
     * to see what, if anything, is left unfilled.
     */
    private void finalizeStatus(EngineOrder order) {
        if (order.getRemainingQuantity().signum() == 0) {
            order.setStatus(OrderStatus.FILLED);
        } else if (order.getRemainingQuantity().compareTo(order.getQuantity()) < 0) {
            order.setStatus(OrderStatus.PARTIALLY_FILLED);
        } else {
            order.setStatus(OrderStatus.OPEN);
        }
    }

    private static List<PriceLevel> toPriceLevels(TreeMap<BigDecimal, Deque<EngineOrder>> levels) {
        List<PriceLevel> result = new ArrayList<>();
        for (Map.Entry<BigDecimal, Deque<EngineOrder>> entry : levels.entrySet()) {
            BigDecimal total = BigDecimal.ZERO;
            for (EngineOrder order : entry.getValue()) {
                total = total.add(order.getRemainingQuantity());
            }
            result.add(new PriceLevel(entry.getKey(), total));
        }
        return result;
    }
}
