package com.trademesh.backend.engine;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single execution produced by the matching engine. No {@code executedAt} —
 * assigning that timestamp is a persistence-layer concern for a later phase.
 */
public record EngineTrade(String symbol, UUID buyOrderId, UUID sellOrderId, BigDecimal price, BigDecimal quantity) {
}
