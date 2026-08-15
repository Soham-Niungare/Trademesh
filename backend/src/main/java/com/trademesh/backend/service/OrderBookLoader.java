package com.trademesh.backend.service;

import com.trademesh.backend.engine.EngineOrder;
import com.trademesh.backend.entity.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rebuilds an {@link OrderBookRegistry}'s books from persisted orders. Deliberately
 * has no Spring/JPA dependency of its own — it just takes a list of already-fetched
 * {@link Order} rows — so it's directly unit-testable without a Spring context.
 * Callers (see {@link OrderBookWarmupRunner}) are responsible for fetching the right
 * rows in the right order: oldest first, so time priority within each price level is
 * preserved (see docs/matching-engine.md).
 */
@Component
public class OrderBookLoader {

    public void reload(List<Order> restingOrdersOldestFirst, OrderBookRegistry registry) {
        for (Order order : restingOrdersOldestFirst) {
            EngineOrder engineOrder = EngineOrderMapper.toEngineOrder(order);
            registry.getOrCreate(order.getSymbol()).restoreRestingOrder(engineOrder);
        }
    }
}
