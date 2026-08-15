package com.trademesh.backend.service;

import com.trademesh.backend.engine.EngineOrder;
import com.trademesh.backend.engine.EngineTrade;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.Trade;

import java.time.Instant;
import java.util.UUID;

/** Field-level mapping between the engine's plain-Java types and the JPA entities. */
final class EngineOrderMapper {

    private EngineOrderMapper() {
    }

    static Order newOrderEntity(UUID id, SubmitOrderRequest request) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(request.userId());
        order.setSymbol(request.symbol());
        order.setSide(request.side());
        order.setType(request.type());
        order.setPrice(request.price());
        order.setQuantity(request.quantity());
        order.setRemainingQuantity(request.quantity());
        order.setStatus(OrderStatus.OPEN);
        order.setCreatedAt(Instant.now());
        return order;
    }

    static void applyEngineState(Order orderEntity, EngineOrder engineOrder) {
        orderEntity.setStatus(engineOrder.getStatus());
        orderEntity.setRemainingQuantity(engineOrder.getRemainingQuantity());
    }

    static EngineOrder toEngineOrder(Order order) {
        return new EngineOrder(order.getId(), order.getUserId(), order.getSymbol(), order.getSide(),
                order.getType(), order.getPrice(), order.getQuantity(), order.getRemainingQuantity(),
                order.getStatus());
    }

    static Trade toTradeEntity(EngineTrade trade, Instant executedAt) {
        Trade tradeEntity = new Trade();
        tradeEntity.setSymbol(trade.symbol());
        tradeEntity.setBuyOrderId(trade.buyOrderId());
        tradeEntity.setSellOrderId(trade.sellOrderId());
        tradeEntity.setPrice(trade.price());
        tradeEntity.setQuantity(trade.quantity());
        tradeEntity.setExecutedAt(executedAt);
        return tradeEntity;
    }
}
