package com.example.tenderapp.exception;

/**
 * Thrown when an authenticated user attempts an action they are not permitted to perform
 * (e.g. deleting an owner account, or acting on a user from another company).
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
