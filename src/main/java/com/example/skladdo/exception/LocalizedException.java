package com.example.skladdo.exception;

/**
 * Base class for exceptions whose message is a translation key (resolved by
 * {@link GlobalExceptionHandler} against the request locale) rather than a literal string.
 * Optional {@code args} are substituted into the message via {@code MessageFormat}.
 */
public abstract class LocalizedException extends RuntimeException {

    private final transient Object[] args;

    protected LocalizedException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args;
    }

    /** The i18n message key (stored as the exception message). */
    public String getMessageKey() {
        return getMessage();
    }

    public Object[] getArgs() {
        return args;
    }
}
