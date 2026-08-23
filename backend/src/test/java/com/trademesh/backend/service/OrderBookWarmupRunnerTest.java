package com.trademesh.backend.service;

import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import com.trademesh.backend.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code OrderBookLoaderTest} covers the reload logic by constructing
 * {@code OrderBookLoader} directly, which is right for that class -- it has no Spring
 * dependency. What that leaves untested is the wiring: that a real
 * {@link OrderBookWarmupRunner} bean exists, is an {@link ApplicationRunner} (so Spring
 * actually calls it during startup), and that its four collaborators are injected such
 * that one invocation rebuilds both the in-memory registry and the Redis projection.
 *
 * <p>Both containers are needed here precisely because the runner's job is to leave
 * those two in agreement; with Redis absent, {@code rebuildAll} would silently no-op
 * and half the runner's contract would go unchecked.
 */
@SpringBootTest
@Testcontainers
class OrderBookWarmupRunnerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private OrderBookWarmupRunner warmupRunner;

    @Autowired
    private OrderBookRegistry orderBookRegistry;

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private OrderRepository orderRepository;

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private Order persist(String symbol, OrderSide side, OrderType type, String price,
                           String quantity, String remainingQuantity, OrderStatus status, int ageSeconds) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setSymbol(symbol);
        order.setSide(side);
        order.setType(type);
        order.setPrice(price == null ? null : new BigDecimal(price));
        order.setQuantity(new BigDecimal(quantity));
        order.setRemainingQuantity(new BigDecimal(remainingQuantity));
        order.setStatus(status);
        order.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(ageSeconds));
        return orderRepository.save(order);
    }

    @Test
    void isRegisteredAsAnApplicationRunner_soSpringInvokesItOnStartup() {
        // The whole mechanism depends on this: if the bean stopped implementing
        // ApplicationRunner, every test below would still pass when calling run()
        // by hand, while the real application silently started with an empty book.
        assertInstanceOf(ApplicationRunner.class, warmupRunner);
        assertTrue(applicationContext.getBeansOfType(ApplicationRunner.class).values().stream()
                        .anyMatch(OrderBookWarmupRunner.class::isInstance),
                "OrderBookWarmupRunner must be discoverable as an ApplicationRunner bean");
    }

    @Test
    void run_rebuildsBothTheInMemoryBookAndTheRedisSnapshotFromPostgres() {
        String symbol = "WARMUP-" + UUID.randomUUID();

        persist(symbol, OrderSide.SELL, OrderType.LIMIT, "100", "50", "50", OrderStatus.OPEN, 0);
        persist(symbol, OrderSide.SELL, OrderType.LIMIT, "100", "30", "30", OrderStatus.OPEN, 1);
        persist(symbol, OrderSide.BUY, OrderType.LIMIT, "95", "20", "12", OrderStatus.PARTIALLY_FILLED, 2);
        persist(symbol, OrderSide.SELL, OrderType.LIMIT, "105", "10", "0", OrderStatus.FILLED, 3);
        persist(symbol, OrderSide.BUY, OrderType.LIMIT, "90", "5", "5", OrderStatus.CANCELLED, 4);

        warmupRunner.run(new DefaultApplicationArguments());

        // In-memory registry: only genuinely resting orders, aggregated per level.
        OrderBookSnapshot inMemory = orderBookRegistry.getOrCreate(symbol).getSnapshot();
        assertEquals(1, inMemory.asks().size());
        assertMoneyEquals("100", inMemory.asks().get(0).price());
        assertMoneyEquals("80", inMemory.asks().get(0).totalQuantity());
        assertEquals(1, inMemory.bids().size());
        assertMoneyEquals("12", inMemory.bids().get(0).totalQuantity());

        // Redis projection: rebuilt from the same query, so it must agree exactly.
        OrderBookSnapshot fromRedis = marketDataService.getSnapshot(symbol);
        assertEquals(inMemory.asks().size(), fromRedis.asks().size());
        assertMoneyEquals("100", fromRedis.asks().get(0).price());
        assertMoneyEquals("80", fromRedis.asks().get(0).totalQuantity());
        assertMoneyEquals("12", fromRedis.bids().get(0).totalQuantity());
    }

    /**
     * Regression for the Phase 5 startup bug, asserted at the level where it actually
     * bit. A MARKET order that never fully filled is persisted with status OPEN, and
     * {@code OrderBook.restoreRestingOrder} rejects non-LIMIT orders outright -- so
     * before the repository query filtered on type, this row made the runner throw.
     * Because the runner is an {@code ApplicationRunner}, that throw failed
     * application startup outright, on every boot, for as long as the row existed.
     * {@code OrderBookLoaderTest} covers the query/loader half; this covers the
     * "startup does not die" half.
     */
    @Test
    void run_withAnUnfilledMarketOrderInThePast_doesNotThrow() {
        String symbol = "WARMUP-MKT-" + UUID.randomUUID();

        persist(symbol, OrderSide.SELL, OrderType.LIMIT, "100", "10", "10", OrderStatus.OPEN, 0);
        persist(symbol, OrderSide.BUY, OrderType.MARKET, null, "8", "8", OrderStatus.OPEN, 1);

        assertDoesNotThrow(() -> warmupRunner.run(new DefaultApplicationArguments()));

        OrderBookSnapshot snapshot = orderBookRegistry.getOrCreate(symbol).getSnapshot();
        assertMoneyEquals("10", snapshot.asks().get(0).totalQuantity());
        assertTrue(snapshot.bids().isEmpty(), "the MARKET order's quantity must not leak into the book");
    }
}
