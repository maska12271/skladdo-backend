package com.example.kladdo.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns exceptions into JSON {@code {"error": "..."}} responses whose message is translated to the
 * caller's language (resolved from the {@code Accept-Language} header — see
 * {@link com.example.kladdo.config.I18nConfig}). Business exceptions carry a translation key
 * ({@link LocalizedException}); everything else maps to a friendly, actionable generic message so
 * users never see a raw stack trace or an untranslated internal string.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(ResourceNotFoundException ex) {
        // The specific "X not found with id: N" text is kept for server logs only; the user gets a
        // generic, translated message (the page they're on already tells them what was missing).
        log.debug("Resource not found: {}", ex.getMessage());
        return error(resolve("error.notFound"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        // FieldError is MessageSourceResolvable: this resolves its constraint code (NotBlank, Size,
        // ...) against our bundles, falling back to the annotation's default message if unmapped.
        String message = fieldError != null
                ? messageSource.getMessage(fieldError, LocaleContextHolder.getLocale())
                : resolve("error.validation.fallback");
        return error(message);
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBusinessRule(BadRequestException ex) {
        return error(resolve(ex.getMessageKey(), ex.getArgs()));
    }

    /**
     * Residual {@link IllegalArgumentException}/{@link IllegalStateException} are internal invariants
     * (e.g. "no authenticated user in the security context"), not user-facing rules — those now throw
     * {@link BadRequestException}. Return a generic translated message so internals never leak.
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(RuntimeException ex) {
        log.warn("Unexpected {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return error(resolve("error.badRequest.generic"));
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> handleAuthentication(AuthenticationException ex) {
        return error(resolve("error.auth.invalidCredentials"));
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleForbidden(ForbiddenException ex) {
        return error(resolve(ex.getMessageKey(), ex.getArgs()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleAccessDenied(AccessDeniedException ex) {
        return error(resolve("error.accessDenied"));
    }

    /**
     * Turns raw database constraint failures into a friendly 409 instead of a 500 + stack trace. Covers
     * the two common cases: deleting a record that other records still reference (foreign key), and
     * saving a value that must be unique but already exists. Distinguished by SQLSTATE (23503 = foreign
     * key, 23505 = unique) which is standard across H2/PostgreSQL.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleDataIntegrity(DataIntegrityViolationException ex) {
        String sqlState = sqlStateOf(ex);
        String key;
        if ("23503".equals(sqlState)) {
            key = "error.conflict.foreignKey";
        } else if ("23505".equals(sqlState)) {
            key = "error.conflict.unique";
        } else {
            key = "error.conflict.generic";
        }
        return error(resolve(key));
    }

    /**
     * Last-resort handler: anything not mapped above becomes a friendly, translated 500 instead of a
     * raw stack trace. Exceptions that already carry their own HTTP status (Spring's MVC exceptions,
     * {@code ResponseStatusException}, etc. — all {@link ErrorResponse}) are rethrown so their proper
     * status codes (404/405/415/…) are preserved.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof ErrorResponse) {
            throw ex;
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(resolve("error.server")));
    }

    /** Walks the cause chain to find the SQLSTATE of the underlying {@link SQLException}, if any. */
    private static String sqlStateOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Resolves a translation key against the request locale, using the key itself as a safe fallback. */
    private String resolve(String key, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        // Empty varargs -> null so MessageSource skips MessageFormat (leaves apostrophes untouched).
        Object[] formatArgs = (args == null || args.length == 0) ? null : args;
        return messageSource.getMessage(key, formatArgs, key, locale);
    }

    private Map<String, String> error(String message) {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return error;
    }
}
