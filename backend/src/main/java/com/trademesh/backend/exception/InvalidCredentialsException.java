package com.trademesh.backend.exception;

/**
 * Thrown for a failed login, whether the username doesn't exist or the password is
 * wrong -- deliberately one exception (and one message) for both, so the response
 * can't be used to enumerate valid usernames. Maps to 401.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
