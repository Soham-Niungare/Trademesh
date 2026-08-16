package com.trademesh.backend.exception;

/**
 * Thrown both when an order genuinely doesn't exist and when it exists but belongs
 * to a different user -- deliberately the same exception (and 404) for both, so a
 * non-owner can't distinguish "not found" from "not yours" by response. Maps to 404.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
