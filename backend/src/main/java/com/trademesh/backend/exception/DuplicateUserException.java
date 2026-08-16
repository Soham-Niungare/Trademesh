package com.trademesh.backend.exception;

/** Thrown when registration collides with an existing username or email. Maps to 409. */
public class DuplicateUserException extends RuntimeException {

    public DuplicateUserException(String message) {
        super(message);
    }
}
