package com.trademesh.backend.service;

import com.trademesh.backend.engine.MatchResult;

import java.util.UUID;

/**
 * Result of {@link TradeService#submitOrder(SubmitOrderRequest)}: the persisted
 * incoming order's id, plus the engine's {@link MatchResult}. The engine itself
 * has no notion of a persisted id at submission time, so the service layer adds
 * it here rather than forcing every caller to generate and thread one through.
 */
public record SubmitOrderResult(UUID orderId, MatchResult matchResult) {
}
