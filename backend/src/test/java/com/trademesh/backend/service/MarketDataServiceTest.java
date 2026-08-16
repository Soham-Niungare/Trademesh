package com.trademesh.backend.service;

import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import com.trademesh.backend.repository.OrderRepository;
import com.trademesh.backend.repository.TradeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The positive-path Redis integration test: a real, ephemeral Postgres AND a real,
 * ephemeral Redis (Testcontainers for both), proving that placing/matching/
 * cancelling orders through the real TradeService produces a correct Redis
 * snapshot, readable back through MarketDataService/the same JSON shape
 * MarketController serves. Redis is wired via a plain GenericContainer +
 * @DynamicPropertySource rather than @ServiceConnection: there's no built-in
 * Spring Boot service-connection factory for a bare GenericContainer pointed at
 * a "redis" image, and this avoids any doubt about whether one applies here.
 */
@SpringBootTest
@Testcontainers
class MarketDataServiceTest {

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
    private TradeService tradeService;

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @AfterEach
    void cleanUp() {
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private static String uniqueSymbol() {
        return "SYM-" + UUID.randomUUID();
    }

    private static SubmitOrderRequest limit(OrderSide side, String symbol, String price, String quantity) {
        return new SubmitOrderRequest(UUID.randomUUID(), symbol, side, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(quantity));
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void placingRestingOrders_updatesRedisSnapshotWithAggregatedDepth() {
        String symbol = uniqueSymbol();

        tradeService.submitOrder(limit(OrderSide.SELL, symbol, "101", "30"));
        tradeService.submitOrder(limit(OrderSide.SELL, symbol, "101", "20"));
        tradeService.submitOrder(limit(OrderSide.BUY, symbol, "99", "15"));

        OrderBookSnapshot snapshot = marketDataService.getSnapshot(symbol);

        assertEquals(1, snapshot.asks().size());
        assertMoneyEquals("101", snapshot.asks().get(0).price());
        assertMoneyEquals("50", snapshot.asks().get(0).totalQuantity());

        assertEquals(1, snapshot.bids().size());
        assertMoneyEquals("99", snapshot.bids().get(0).price());
        assertMoneyEquals("15", snapshot.bids().get(0).totalQuantity());
    }

    @Test
    void matchingOrder_removesConsumedLiquidityFromRedisSnapshot() {
        String symbol = uniqueSymbol();

        tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "10"));
        SubmitOrderResult buyResult = tradeService.submitOrder(limit(OrderSide.BUY, symbol, "100", "10"));

        assertEquals(1, buyResult.matchResult().trades().size());

        OrderBookSnapshot snapshot = marketDataService.getSnapshot(symbol);
        assertTrue(snapshot.asks().isEmpty());
        assertTrue(snapshot.bids().isEmpty());
    }

    @Test
    void cancellingOrder_removesItFromRedisSnapshot() {
        String symbol = uniqueSymbol();

        SubmitOrderResult result = tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "10"));
        assertEquals(1, marketDataService.getSnapshot(symbol).asks().size());

        assertTrue(tradeService.cancelOrder(result.orderId()));

        assertTrue(marketDataService.getSnapshot(symbol).asks().isEmpty());
    }

    @Test
    void getSnapshot_forSymbolWithNoOrders_returnsEmptySnapshotNotError() {
        OrderBookSnapshot snapshot = marketDataService.getSnapshot("NEVER-TRADED-" + UUID.randomUUID());

        assertTrue(snapshot.bids().isEmpty());
        assertTrue(snapshot.asks().isEmpty());
    }

    @Test
    void rebuildSnapshot_recomputesFromPostgresRegardlessOfWhatRedisCurrentlyHolds() {
        String symbol = uniqueSymbol();

        tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "10"));

        // Simulate stale/corrupt Redis data directly, bypassing MarketDataService
        // entirely, to prove rebuildSnapshot() genuinely recomputes from Postgres
        // rather than trusting whatever Redis currently holds.
        redisTemplate.opsForValue().set("marketdata:orderbook:" + symbol, "not even valid json");

        marketDataService.rebuildSnapshot(symbol);

        OrderBookSnapshot snapshot = marketDataService.getSnapshot(symbol);
        assertEquals(1, snapshot.asks().size());
        assertMoneyEquals("100", snapshot.asks().get(0).price());
        assertMoneyEquals("10", snapshot.asks().get(0).totalQuantity());
    }

    @Test
    void rebuildAll_correctlyGroupsMultipleSymbolsFromOneBatch() {
        String symbolA = uniqueSymbol();
        String symbolB = uniqueSymbol();

        Order orderA = plainLimitOrder(symbolA, OrderSide.SELL, "10", "1");
        Order orderB = plainLimitOrder(symbolB, OrderSide.BUY, "20", "2");

        marketDataService.rebuildAll(List.of(orderA, orderB));

        assertMoneyEquals("1", marketDataService.getSnapshot(symbolA).asks().get(0).totalQuantity());
        assertMoneyEquals("2", marketDataService.getSnapshot(symbolB).bids().get(0).totalQuantity());
    }

    private static Order plainLimitOrder(String symbol, OrderSide side, String price, String quantity) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setSymbol(symbol);
        order.setSide(side);
        order.setType(OrderType.LIMIT);
        order.setPrice(new BigDecimal(price));
        order.setQuantity(new BigDecimal(quantity));
        order.setRemainingQuantity(new BigDecimal(quantity));
        order.setStatus(OrderStatus.OPEN);
        order.setCreatedAt(Instant.now());
        return order;
    }
}
