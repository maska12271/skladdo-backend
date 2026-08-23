package com.example.skladdo.exception;

/**
 * Thrown when a caller has made too many attempts at a rate-limited endpoint (mapped to HTTP 429).
 * Carries a translation key so {@link GlobalExceptionHandler} can return the message in the caller's
 * language — typically with the number of minutes left before they may try again.
 */
public class TooManyRequestsException extends LocalizedException {

    public TooManyRequestsException(String messageKey, Object... args) {
        super(messageKey, args);
    }
}
