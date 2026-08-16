package com.trademesh.backend.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Deliberately has no userId field. {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * means a client-supplied "userId" (or any other unrecognized field) in the JSON body
 * is silently dropped rather than rejected or read -- the authenticated caller's id
 * from the JWT (see OrderController) is the only source of truth for order ownership.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateOrderRequest(
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull OrderType type,
        BigDecimal price,
        @NotNull @Positive BigDecimal quantity) {
}
