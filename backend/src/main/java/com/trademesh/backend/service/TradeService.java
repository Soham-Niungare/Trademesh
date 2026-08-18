package com.trademesh.backend.service;

import com.trademesh.backend.engine.EngineOrder;
import com.trademesh.backend.engine.EngineTrade;
import com.trademesh.backend.engine.MatchResult;
import com.trademesh.backend.engine.OrderBook;
import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.event.OrderCancelledEvent;
import com.trademesh.backend.event.OrderStateChange;
import com.trademesh.backend.event.TradeExecutedEvent;
import com.trademesh.backend.repository.OrderRepository;
import com.trademesh.backend.repository.TradeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wires the in-memory matching engine to Postgres: submitting or cancelling an
 * order runs against the live {@link OrderBook} and persists the outcome in one
 * transaction, then -- strictly after that transaction has committed -- publishes
 * a {@link TradeExecutedEvent}/{@link OrderCancelledEvent} via a plain
 * {@link ApplicationEventPublisher}. This class no longer references
 * {@code MarketDataService} (or anything else downstream) directly at all;
 * whatever reacts to a trade/cancellation -- the Redis snapshot refresh, the
 * WebSocket broadcast -- does so as an independent {@code @EventListener},
 * decoupled from this class and from each other. See docs/architecture.md for
 * the known limitation this still implies (the in-memory book isn't itself
 * transactional, so it can drift ahead of Postgres if a persistence write fails
 * after a match already happened) and docs/websocket.md for the event design.
 *
 * The DB work is deliberately NOT a plain {@code @Transactional} method: with
 * AOP-proxied {@code @Transactional}, the commit happens when the proxied method
 * call returns to ITS caller, not at the last line of the method body -- so code
 * at the end of an {@code @Transactional submitOrder(...)} would still run before
 * commit, not after it. {@link TransactionTemplate} demarcates the transaction
 * explicitly instead: {@code execute(...)} only returns once that transaction has
 * committed (or been rolled back), so the event published below it is genuinely
 * post-commit. For the same reason, the event is published from the PUBLIC method
 * after {@code execute(...)} returns -- never from inside the callback passed to
 * it, and never via {@code @TransactionalEventListener}, which would just
 * reintroduce the same proxy/self-invocation subtlety in a different form.
 */
@Service
public class TradeService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final OrderBookRegistry orderBookRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public TradeService(OrderRepository orderRepository, TradeRepository tradeRepository,
                         OrderBookRegistry orderBookRegistry, ApplicationEventPublisher eventPublisher,
                         TransactionTemplate transactionTemplate) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.orderBookRegistry = orderBookRegistry;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    public SubmitOrderResult submitOrder(SubmitOrderRequest request) {
        SubmitOrderResult result = transactionTemplate.execute(status -> doSubmitOrder(request));

        MatchResult matchResult = result.matchResult();
        OrderStateChange incomingOrderChange = new OrderStateChange(result.orderId(), request.userId(),
                request.symbol(), matchResult.finalStatus(), matchResult.remainingQuantity());
        List<OrderStateChange> affectedOrderChanges = matchResult.affectedRestingOrders().stream()
                .map(OrderStateChange::from)
                .toList();
        OrderBookSnapshot snapshot = orderBookRegistry.getOrCreate(request.symbol()).getSnapshot();

        eventPublisher.publishEvent(new TradeExecutedEvent(request.symbol(), matchResult.trades(),
                incomingOrderChange, affectedOrderChanges, snapshot));

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
            OrderBookSnapshot snapshot = orderBookRegistry.getOrCreate(outcome.symbol()).getSnapshot();
            eventPublisher.publishEvent(new OrderCancelledEvent(outcome.symbol(), outcome.cancelledOrder(), snapshot));
        }
        return outcome.cancelled();
    }

    private CancelOutcome doCancelOrder(UUID orderId) {
        Order orderEntity = orderRepository.findById(orderId).orElse(null);
        if (orderEntity == null) {
            return new CancelOutcome(false, null, null);
        }

        OrderBook book = orderBookRegistry.getOrCreate(orderEntity.getSymbol());
        if (!book.cancelOrder(orderId)) {
            return new CancelOutcome(false, orderEntity.getSymbol(), null);
        }

        orderEntity.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(orderEntity);
        return new CancelOutcome(true, orderEntity.getSymbol(), OrderStateChange.from(orderEntity));
    }

    private record CancelOutcome(boolean cancelled, String symbol, OrderStateChange cancelledOrder) {
    }
}
