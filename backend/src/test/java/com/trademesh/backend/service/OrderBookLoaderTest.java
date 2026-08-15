package com.trademesh.backend.service;

import com.trademesh.backend.engine.EngineOrder;
import com.trademesh.backend.engine.MatchResult;
import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import com.trademesh.backend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the reload path (persist resting orders -> query them back -> feed
 * them through OrderBookLoader -> assert the rebuilt book's snapshot) exactly as
 * it runs on startup via OrderBookWarmupRunner, minus the Spring wiring:
 * OrderBookLoader/OrderBookRegistry are plain classes with no Spring dependency
 * of their own, so they're constructed directly here rather than autowired.
 * @DataJpaTest only needs to supply a real OrderRepository/Postgres to persist
 * and read back the seed data.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderBookLoaderTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private OrderRepository orderRepository;

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private int sequenceCounter = 0;

    private Order persistOrder(String symbol, OrderSide side, String price, String quantity,
                                String remainingQuantity, OrderStatus status) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setSymbol(symbol);
        order.setSide(side);
        order.setType(OrderType.LIMIT);
        order.setPrice(new BigDecimal(price));
        order.setQuantity(new BigDecimal(quantity));
        order.setRemainingQuantity(new BigDecimal(remainingQuantity));
        order.setStatus(status);
        // Explicit, strictly-increasing timestamps rather than relying on
        // Instant.now() resolution to disambiguate arrival order between calls.
        order.setCreatedAt(BASE_INSTANT.plusSeconds(sequenceCounter++));
        return orderRepository.save(order);
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void reload_rebuildsBookFromPersistedRestingOrdersAndPreservesTimePriority() {
        String symbol = "RELOAD-" + UUID.randomUUID();

        Order olderRestingSell = persistOrder(symbol, OrderSide.SELL, "100", "50", "50", OrderStatus.OPEN);
        persistOrder(symbol, OrderSide.SELL, "100", "30", "30", OrderStatus.OPEN); // same price, arrived later
        persistOrder(symbol, OrderSide.BUY, "95", "20", "12", OrderStatus.PARTIALLY_FILLED);
        persistOrder(symbol, OrderSide.SELL, "105", "10", "0", OrderStatus.FILLED); // must be excluded from reload
        persistOrder(symbol, OrderSide.BUY, "90", "5", "0", OrderStatus.CANCELLED); // must be excluded from reload

        List<Order> restingOrders = orderRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED));

        OrderBookRegistry registry = new OrderBookRegistry();
        new OrderBookLoader().reload(restingOrders, registry);

        OrderBookSnapshot snapshot = registry.getOrCreate(symbol).getSnapshot();

        assertEquals(1, snapshot.asks().size());
        assertMoneyEquals("100", snapshot.asks().get(0).price());
        assertMoneyEquals("80", snapshot.asks().get(0).totalQuantity()); // 50 + 30, FILLED/CANCELLED excluded

        assertEquals(1, snapshot.bids().size());
        assertMoneyEquals("95", snapshot.bids().get(0).price());
        assertMoneyEquals("12", snapshot.bids().get(0).totalQuantity());

        // Time priority survived the reload: the older same-price sell fills first.
        EngineOrder incomingBuy = new EngineOrder(UUID.randomUUID(), UUID.randomUUID(), symbol, OrderSide.BUY,
                OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("10"));
        MatchResult matchResult = registry.getOrCreate(symbol).submitOrder(incomingBuy);

        assertEquals(1, matchResult.trades().size());
        assertEquals(olderRestingSell.getId(), matchResult.trades().get(0).sellOrderId());
    }
}
