package com.trademesh.backend.service;

import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import com.trademesh.backend.entity.Trade;
import com.trademesh.backend.repository.OrderRepository;
import com.trademesh.backend.repository.TradeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves constraint #3 directly: with Redis genuinely unreachable for this whole
 * test class, order placement, matching, and cancellation must all still succeed
 * and persist correctly to Postgres. No Redis container is started at all here --
 * spring.data.redis.port is pointed at port 1 on localhost, where nothing is ever
 * listening, so every Redis call fails fast and deterministically (connection
 * refused, not a slow timeout) without needing to manage a container's
 * stop/start lifecycle or risk affecting other test classes' shared state.
 */
@SpringBootTest
@Testcontainers
class MarketDataResilienceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 1);
    }

    @Autowired
    private TradeService tradeService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @AfterEach
    void cleanUp() {
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
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
    void orderPlacementMatchingAndCancellation_allSucceedAndPersistWithRedisUnreachable() {
        String symbol = "SYM-" + UUID.randomUUID();

        SubmitOrderResult sellResult = tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "10"));
        SubmitOrderResult buyResult = tradeService.submitOrder(limit(OrderSide.BUY, symbol, "100", "10"));

        assertEquals(1, buyResult.matchResult().trades().size());

        Order sellOrder = orderRepository.findById(sellResult.orderId()).orElseThrow();
        Order buyOrder = orderRepository.findById(buyResult.orderId()).orElseThrow();
        assertEquals(OrderStatus.FILLED, sellOrder.getStatus());
        assertEquals(OrderStatus.FILLED, buyOrder.getStatus());

        List<Trade> trades = tradeRepository.findBySymbolOrderByExecutedAtDesc(symbol);
        assertEquals(1, trades.size());
        assertMoneyEquals("10", trades.get(0).getQuantity());

        // The other TradeService entry point that now also triggers a post-commit
        // Redis update -- must be equally unaffected.
        SubmitOrderResult restingOrder = tradeService.submitOrder(limit(OrderSide.SELL, symbol, "200", "5"));
        assertTrue(tradeService.cancelOrder(restingOrder.orderId()));
        assertEquals(OrderStatus.CANCELLED, orderRepository.findById(restingOrder.orderId()).orElseThrow().getStatus());
    }
}
