package com.trademesh.backend.engine;

import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Order representation used by the matching engine. Deliberately separate from
 * {@link com.trademesh.backend.entity.Order} (the JPA entity) so the engine has
 * no dependency on persistence.
 */
public class EngineOrder {

    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong();

    private final UUID id;
    private final UUID userId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final long sequence;

    private BigDecimal remainingQuantity;
    private OrderStatus status;

    public EngineOrder(UUID id, UUID userId, String symbol, OrderSide side, OrderType type,
                        BigDecimal price, BigDecimal quantity) {
        if (type == OrderType.LIMIT && price == null) {
            throw new IllegalArgumentException("price is required for LIMIT orders");
        }
        this.id = id;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.status = OrderStatus.OPEN;
        this.sequence = SEQUENCE_GENERATOR.incrementAndGet();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getType() {
        return type;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getRemainingQuantity() {
        return remainingQuantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    /** Arrival order, used to break ties between orders resting at the same price. */
    public long getSequence() {
        return sequence;
    }

    void reduceRemainingQuantity(BigDecimal filledQuantity) {
        this.remainingQuantity = this.remainingQuantity.subtract(filledQuantity);
    }

    void setStatus(OrderStatus status) {
        this.status = status;
    }
}
