package com.trademesh.backend.service;

import com.trademesh.backend.engine.OrderBookSnapshot;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Runs TradeService against a real, ephemeral PostgreSQL container (Testcontainers)
 * rather than an embedded/emulated database — see docs/phases/phase-03-trade-execution.md
 * for why. Deliberately does NOT rely on Spring's test-managed transaction rollback:
 * each test's own repository calls and each tradeService call commit for real, and
 * @AfterEach wipes the tables. That matters specifically for the rollback test below,
 * which needs to observe genuinely-committed (or genuinely-rolled-back) state rather
 * than reads inside a single not-yet-committed test transaction.
 */
@SpringBootTest
@Testcontainers
class TradeServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private TradeService tradeService;

    @Autowired
    private OrderRepository orderRepository;

    @SpyBean
    private TradeRepository tradeRepository;

    @Autowired
    private OrderBookRegistry orderBookRegistry;

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

    private static SubmitOrderRequest market(OrderSide side, String symbol, String quantity) {
        return new SubmitOrderRequest(UUID.randomUUID(), symbol, side, OrderType.MARKET, null, new BigDecimal(quantity));
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void fullyMatchingOrder_persistsOneTradeAndBothOrdersFilled() {
        String symbol = uniqueSymbol();

        SubmitOrderResult sellResult = tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "50"));
        SubmitOrderResult buyResult = tradeService.submitOrder(limit(OrderSide.BUY, symbol, "100", "50"));

        assertEquals(1, buyResult.matchResult().trades().size());

        List<Trade> trades = tradeRepository.findBySymbolOrderByExecutedAtDesc(symbol);
        assertEquals(1, trades.size());
        Trade trade = trades.get(0);
        assertMoneyEquals("50", trade.getQuantity());
        assertMoneyEquals("100", trade.getPrice());
        assertEquals(buyResult.orderId(), trade.getBuyOrderId());
        assertEquals(sellResult.orderId(), trade.getSellOrderId());

        Order buyOrder = orderRepository.findById(buyResult.orderId()).orElseThrow();
        Order sellOrder = orderRepository.findById(sellResult.orderId()).orElseThrow();
        assertEquals(OrderStatus.FILLED, buyOrder.getStatus());
        assertEquals(OrderStatus.FILLED, sellOrder.getStatus());
        assertMoneyEquals("0", buyOrder.getRemainingQuantity());
        assertMoneyEquals("0", sellOrder.getRemainingQuantity());
    }

    @Test
    void partialFill_persistsAccurateRemainingQuantityAndStatus() {
        String symbol = uniqueSymbol();

        SubmitOrderResult sellResult = tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "100"));
        SubmitOrderResult buyResult = tradeService.submitOrder(limit(OrderSide.BUY, symbol, "100", "30"));

        assertEquals(1, buyResult.matchResult().trades().size());

        Order sellOrder = orderRepository.findById(sellResult.orderId()).orElseThrow();
        Order buyOrder = orderRepository.findById(buyResult.orderId()).orElseThrow();

        assertEquals(OrderStatus.PARTIALLY_FILLED, sellOrder.getStatus());
        assertMoneyEquals("70", sellOrder.getRemainingQuantity());

        assertEquals(OrderStatus.FILLED, buyOrder.getStatus());
        assertMoneyEquals("0", buyOrder.getRemainingQuantity());
    }

    @Test
    void marketOrderWithNoLiquidity_persistsOrderButNoTrades() {
        String symbol = uniqueSymbol();

        SubmitOrderResult result = tradeService.submitOrder(market(OrderSide.BUY, symbol, "10"));

        assertTrue(result.matchResult().trades().isEmpty());
        assertTrue(tradeRepository.findBySymbolOrderByExecutedAtDesc(symbol).isEmpty());

        Order order = orderRepository.findById(result.orderId()).orElseThrow();
        assertEquals(OrderStatus.OPEN, order.getStatus());
        assertMoneyEquals("10", order.getRemainingQuantity());
    }

    @Test
    void cancelOrder_removesFromLiveBookAndUpdatesDbStatus() {
        String symbol = uniqueSymbol();

        SubmitOrderResult sellResult = tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "50"));

        assertTrue(tradeService.cancelOrder(sellResult.orderId()));

        Order order = orderRepository.findById(sellResult.orderId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        OrderBookSnapshot snapshot = orderBookRegistry.getOrCreate(symbol).getSnapshot();
        assertTrue(snapshot.asks().isEmpty());

        SubmitOrderResult buyResult = tradeService.submitOrder(limit(OrderSide.BUY, symbol, "100", "50"));
        assertTrue(buyResult.matchResult().trades().isEmpty());
    }

    @Test
    void cancelOrder_unknownId_returnsFalse() {
        assertFalse(tradeService.cancelOrder(UUID.randomUUID()));
    }

    /**
     * Forces a persistence failure mid-flow (via a spy that throws on the trade
     * save) rather than a genuine constraint violation: TradeService always
     * builds valid rows, so there's no reachable path where the service's own
     * correct code produces a constraint violation. The spy stands in for any
     * real-world persistence failure (constraint violation, lost connection,
     * deadlock) at that same point in the flow.
     *
     * This proves both halves of the documented limitation
     * (docs/architecture.md): the DB side is genuinely transactional (no
     * partial writes survive), but the in-memory OrderBook is not (it already
     * consumed the resting order before the DB write failed, and that mutation
     * is not undone).
     */
    @Test
    void persistenceFailureMidFlow_rollsBackDbButLeavesInMemoryBookAheadOfIt() {
        String symbol = uniqueSymbol();

        SubmitOrderResult sellResult = tradeService.submitOrder(limit(OrderSide.SELL, symbol, "100", "50"));
        assertEquals(1, orderRepository.count());

        doThrow(new DataIntegrityViolationException("simulated persistence failure"))
                .when(tradeRepository).save(any(Trade.class));

        SubmitOrderRequest buyRequest = limit(OrderSide.BUY, symbol, "100", "50");
        assertThrows(DataIntegrityViolationException.class, () -> tradeService.submitOrder(buyRequest));

        // DB: rolled back cleanly. No trade, and only the original sell order
        // (the failed buy order's own insert was rolled back too), untouched.
        assertTrue(tradeRepository.findBySymbolOrderByExecutedAtDesc(symbol).isEmpty());
        assertEquals(1, orderRepository.count());
        Order sellOrderAfterFailure = orderRepository.findById(sellResult.orderId()).orElseThrow();
        assertEquals(OrderStatus.OPEN, sellOrderAfterFailure.getStatus());
        assertMoneyEquals("50", sellOrderAfterFailure.getRemainingQuantity());

        // In-memory: already diverged. The book matched and consumed the sell
        // order before the DB write failed, and that isn't undone by the
        // transaction rollback -- exactly the documented limitation.
        OrderBookSnapshot snapshotAfterFailure = orderBookRegistry.getOrCreate(symbol).getSnapshot();
        assertTrue(snapshotAfterFailure.asks().isEmpty(),
                "in-memory book already removed the sell order even though the DB write rolled back");
    }
}
