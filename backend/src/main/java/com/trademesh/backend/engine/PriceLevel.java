package com.trademesh.backend.engine;

import java.math.BigDecimal;

/** One price and the aggregate remaining quantity resting at that price. */
public record PriceLevel(BigDecimal price, BigDecimal totalQuantity) {
}
