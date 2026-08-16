package com.trademesh.backend.exception;

/** Thrown when cancelling an order that's no longer resting (already FILLED/CANCELLED, or never rested). Maps to 409. */
public class OrderNotCancellableException extends RuntimeException {

    public OrderNotCancellableException(String message) {
        super(message);
    }
}
