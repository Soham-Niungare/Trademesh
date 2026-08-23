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
        /*
         * @Positive but deliberately NOT @NotNull: Bean Validation treats null as
         * valid for @Positive, which is exactly the behaviour needed here. A MARKET
         * order legitimately carries no price and must still be accepted, while
         * "LIMIT with no price" is rejected further in, by EngineOrder's own
         * invariant -- the engine owns that rule, so it isn't restated here.
         *
         * What @Positive adds is the case nothing checked before: a negative or zero
         * price on a LIMIT order used to pass validation, pass EngineOrder's
         * null-check, and rest in the book at a nonsensical price.
         */
        @Positive BigDecimal price,
        @NotNull @Positive BigDecimal quantity) {
}
