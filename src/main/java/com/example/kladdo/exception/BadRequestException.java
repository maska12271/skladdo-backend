package com.example.kladdo.exception;

/**
 * Thrown when a request violates a business rule (mapped to HTTP 400). Carries a translation key
 * so {@link GlobalExceptionHandler} can return the message in the caller's language.
 */
public class BadRequestException extends LocalizedException {

    public BadRequestException(String messageKey, Object... args) {
        super(messageKey, args);
    }
}
