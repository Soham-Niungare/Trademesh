package com.trademesh.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Price(String symbol, BigDecimal price, Instant updatedAt) {
}
