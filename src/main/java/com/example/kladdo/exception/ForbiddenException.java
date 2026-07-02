package com.example.kladdo.exception;

/**
 * Thrown when an authenticated user attempts an action they are not permitted to perform
 * (e.g. deleting an owner account, or acting on a user from another company). The message is a
 * translation key resolved by {@link GlobalExceptionHandler} against the request locale.
 */
public class ForbiddenException extends LocalizedException {

    public ForbiddenException(String messageKey, Object... args) {
        super(messageKey, args);
    }
}
