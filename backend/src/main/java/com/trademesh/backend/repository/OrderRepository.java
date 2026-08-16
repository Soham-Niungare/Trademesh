package com.trademesh.backend.repository;

import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Used on startup to rebuild both the in-memory book and the Redis market-data
     * snapshot; ordering preserves time priority. Filters to {@code type} as well
     * as status: a MARKET order that doesn't fully fill is still persisted with
     * status OPEN/PARTIALLY_FILLED (see docs/matching-engine.md), but it never
     * rested in any book -- {@code OrderBook.restoreRestingOrder} rejects non-LIMIT
     * orders outright, so a plain status filter here would crash startup on the
     * very first market order that didn't fully fill.
     */
    List<Order> findByTypeAndStatusInOrderByCreatedAtAsc(OrderType type, Collection<OrderStatus> statuses);

    /** Same LIMIT-only "resting orders" semantics as above, scoped to one symbol -- used for an on-demand market-data rebuild. */
    List<Order> findBySymbolAndTypeAndStatusIn(String symbol, OrderType type, Collection<OrderStatus> statuses);
}
