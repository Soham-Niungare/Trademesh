package com.trademesh.backend.service;

import com.trademesh.backend.engine.EngineOrder;
import com.trademesh.backend.engine.EngineTrade;
import com.trademesh.backend.engine.MatchResult;
import com.trademesh.backend.engine.OrderBook;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.repository.OrderRepository;
import com.trademesh.backend.repository.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Wires the in-memory matching engine to Postgres: submitting or cancelling an
 * order runs against the live {@link OrderBook} and persists the outcome in one
 * transaction, then -- strictly after that transaction has committed -- refreshes
 * the symbol's Redis market-data snapshot via {@link MarketDataService}. See
 * docs/architecture.md for the known limitation this implies (the in-memory book
 * isn't itself transactional, so it can drift ahead of Postgres if a persistence
 * write fails after a match already happened).
 *
 * The DB work is deliberately NOT a plain {@code @Transactional} method anymore:
 * with AOP-proxied {@code @Transactional}, the commit happens when the proxied
 * method call returns to ITS caller, not at the last line of the method body --
 * so code at the end of an {@code @Transactional submitOrder(...)} would still run
 * before commit, not after it. {@link TransactionTemplate} demarcates the
 * transaction explicitly instead: {@code execute(...)} only returns once that
 * transaction has committed (or been rolled back), so the market-data refresh
 * below it is genuinely post-commit, not just "last in the method."
 */
@Service
public class TradeService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final OrderBookRegistry orderBookRegistry;
    private final MarketDataService marketDataService;
    private final TransactionTemplate transactionTemplate;

    public TradeService(OrderRepository orderRepository, TradeRepository tradeRepository,
                         OrderBookRegistry orderBookRegistry, MarketDataService marketDataService,
                         TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.orderBookRegistry = orderBookRegistry;
        this.marketDataService = marketDataService;
        this.transactionTemplate = transactionTemplate;
    }

    public SubmitOrderResult submitOrder(SubmitOrderRequest request) {
        SubmitOrderResult result = transactionTemplate.execute(status -> doSubmitOrder(request));
        // Direct, synchronous call -- no event listener, no queue, per design.
        // A Redis failure here is caught and logged inside MarketDataService and
        // never propagates: the trade above is already durably committed.
        marketDataService.updateSnapshot(request.symbol());
        return result;
    }

    private SubmitOrderResult doSubmitOrder(SubmitOrderRequest request) {
        UUID orderId = UUID.randomUUID();

        Order orderEntity = EngineOrderMapper.newOrderEntity(orderId, request);
        orderRepository.save(orderEntity);

        EngineOrder engineOrder = new EngineOrder(orderId, request.userId(), request.symbol(), request.side(),
                request.type(), request.price(), request.quantity());

        OrderBook book = orderBookRegistry.getOrCreate(request.symbol());
        MatchResult result = book.submitOrder(engineOrder);

        Instant executedAt = Instant.now();
        for (EngineTrade trade : result.trades()) {
            tradeRepository.save(EngineOrderMapper.toTradeEntity(trade, executedAt));
        }

        EngineOrderMapper.applyEngineState(orderEntity, engineOrder);
        orderRepository.save(orderEntity);

        for (EngineOrder affected : result.affectedRestingOrders()) {
            Order affectedEntity = orderRepository.findById(affected.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Resting order " + affected.getId() + " matched in-memory but has no DB row"));
            EngineOrderMapper.applyEngineState(affectedEntity, affected);
            orderRepository.save(affectedEntity);
        }

        return new SubmitOrderResult(orderId, result);
    }

    public boolean cancelOrder(UUID orderId) {
        CancelOutcome outcome = transactionTemplate.execute(status -> doCancelOrder(orderId));
        if (outcome.cancelled()) {
            marketDataService.updateSnapshot(outcome.symbol());
        }
        return outcome.cancelled();
    }

    private CancelOutcome doCancelOrder(UUID orderId) {
        Order orderEntity = orderRepository.findById(orderId).orElse(null);
        if (orderEntity == null) {
            return new CancelOutcome(false, null);
        }

        OrderBook book = orderBookRegistry.getOrCreate(orderEntity.getSymbol());
        if (!book.cancelOrder(orderId)) {
            return new CancelOutcome(false, orderEntity.getSymbol());
        }

        orderEntity.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(orderEntity);
        return new CancelOutcome(true, orderEntity.getSymbol());
    }

    private record CancelOutcome(boolean cancelled, String symbol) {
    }
}
