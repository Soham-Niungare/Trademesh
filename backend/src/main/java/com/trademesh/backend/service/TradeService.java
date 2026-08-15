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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Wires the in-memory matching engine to Postgres: submitting or cancelling an
 * order runs against the live {@link OrderBook} and persists the outcome in the
 * same transaction. See docs/architecture.md for the known limitation this
 * implies (the in-memory book isn't itself transactional, so it can drift ahead
 * of Postgres if a persistence write fails after a match already happened).
 */
@Service
public class TradeService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final OrderBookRegistry orderBookRegistry;

    public TradeService(OrderRepository orderRepository, TradeRepository tradeRepository,
                         OrderBookRegistry orderBookRegistry) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.orderBookRegistry = orderBookRegistry;
    }

    @Transactional
    public SubmitOrderResult submitOrder(SubmitOrderRequest request) {
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

    @Transactional
    public boolean cancelOrder(UUID orderId) {
        Order orderEntity = orderRepository.findById(orderId).orElse(null);
        if (orderEntity == null) {
            return false;
        }

        OrderBook book = orderBookRegistry.getOrCreate(orderEntity.getSymbol());
        if (!book.cancelOrder(orderId)) {
            return false;
        }

        orderEntity.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(orderEntity);
        return true;
    }
}
