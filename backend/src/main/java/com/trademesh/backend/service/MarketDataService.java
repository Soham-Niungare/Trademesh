package com.trademesh.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import com.trademesh.backend.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Redis-backed market-data projection: a derived, fully rebuildable view of each
 * symbol's order book. Postgres remains the sole durable source of truth and the
 * in-memory {@code OrderBook} remains the sole authoritative matching-engine state
 * (see docs/architecture.md) -- this class only ever reads from one of those two
 * and writes to Redis, never the reverse, and a Redis failure here is always
 * caught and logged, never allowed to fail whatever caller triggered it.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String KEY_PREFIX = "marketdata:orderbook:";
    private static final List<OrderStatus> RESTING_STATUSES = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderBookRegistry orderBookRegistry;
    private final OrderRepository orderRepository;

    public MarketDataService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              OrderBookRegistry orderBookRegistry, OrderRepository orderRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.orderBookRegistry = orderBookRegistry;
        this.orderRepository = orderRepository;
    }

    /**
     * Refreshes {@code symbol}'s Redis snapshot from the live, authoritative
     * in-memory {@code OrderBook} -- not from Postgres. Called by
     * {@code TradeService} strictly after its DB transaction has already
     * committed (see docs/architecture.md for why the ordering matters).
     */
    public void updateSnapshot(String symbol) {
        writeSnapshot(orderBookRegistry.getOrCreate(symbol).getSnapshot());
    }

    /**
     * Recomputes and stores {@code symbol}'s snapshot directly from Postgres --
     * independent of whatever's currently in Redis, and independent of the
     * in-memory engine too (which could itself have drifted from Postgres; see
     * the Phase 3 limitation in docs/architecture.md). For runtime recovery if
     * Redis data is lost or suspected stale.
     */
    public void rebuildSnapshot(String symbol) {
        List<Order> restingOrders =
                orderRepository.findBySymbolAndTypeAndStatusIn(symbol, OrderType.LIMIT, RESTING_STATUSES);
        writeSnapshot(OrderBookAggregator.aggregate(symbol, restingOrders));
    }

    /**
     * Same idea as {@link #rebuildSnapshot(String)}, for every symbol at once,
     * from an already-fetched list spanning all symbols -- used at startup, fed
     * the exact same query result {@code OrderBookWarmupRunner} uses to rebuild
     * the in-memory engine, so Redis's initial snapshot matches it with no extra
     * Postgres round-trip.
     */
    public void rebuildAll(List<Order> restingOrdersAllSymbols) {
        Map<String, List<Order>> bySymbol = restingOrdersAllSymbols.stream()
                .collect(Collectors.groupingBy(Order::getSymbol));
        bySymbol.forEach((symbol, orders) -> writeSnapshot(OrderBookAggregator.aggregate(symbol, orders)));
    }

    /**
     * Reads {@code symbol}'s current snapshot from Redis. An absent key means "no
     * resting orders," not an error -- returns an empty snapshot, not null. A
     * Redis outage on read is NOT caught here: unlike writes, there's no safe
     * fallback that wouldn't either lie to the caller (silently returning "no
     * orders" when the truth is "unknown") or make every read as expensive as a
     * live rebuild, defeating the point of caching in Redis. GlobalExceptionHandler
     * maps the resulting DataAccessException to 503.
     */
    public OrderBookSnapshot getSnapshot(String symbol) {
        String json = redisTemplate.opsForValue().get(key(symbol));
        if (json == null) {
            return new OrderBookSnapshot(symbol, List.of(), List.of());
        }
        try {
            return objectMapper.readValue(json, OrderBookSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt market-data snapshot in Redis for symbol " + symbol, e);
        }
    }

    private void writeSnapshot(OrderBookSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(key(snapshot.symbol()), json);
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("Failed to update Redis market-data snapshot for symbol {} -- trade/order persistence is unaffected",
                    snapshot.symbol(), e);
        }
    }

    private static String key(String symbol) {
        return KEY_PREFIX + symbol;
    }
}
