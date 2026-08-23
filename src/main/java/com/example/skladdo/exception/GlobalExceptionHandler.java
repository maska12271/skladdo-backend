package com.example.skladdo.exception;

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
 * {@link com.example.skladdo.config.I18nConfig}). Business exceptions carry a translation key
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
     * The request body could not be read at all: malformed JSON, a value of the wrong type, or a string
     * that is not one of an enum's constants. All of these are the caller's mistake, so they are 400.
     *
     * <p>Needed explicitly because {@link org.springframework.http.converter.HttpMessageNotReadableException}
     * does not implement {@link ErrorResponse}, so without a handler here it fell through to
     * {@link #handleUnexpected} and was reported as a 500 "something went wrong on our end" — telling a
     * client with a typo in its payload to contact support.</p>
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        // The underlying parser message can name internal classes, so it is logged rather than returned.
        log.debug("Unreadable request body: {}", ex.getMessage());
        return error(resolve("error.badRequest.malformed"));
    }

    /**
     * A path variable or query parameter that cannot be converted to the expected type, e.g.
     * {@code /api/products/not-a-number}. Also a client error, and also not an {@link ErrorResponse}.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.debug("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        return error(resolve("error.badRequest.malformed"));
    }

    /**
     * A required {@code @RequestParam} (e.g. {@code ?productId=} on the stock ledger) was left off the
     * request entirely. Also not an {@link ErrorResponse}, so without a handler here it fell through to
     * Spring Boot's own default error page instead of this app's translated JSON — which, in dev, includes
     * a full stack trace ({@code server.error.include-stacktrace} defaults to {@code ALWAYS} whenever
     * {@code spring-boot-devtools} is on the classpath).
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        log.debug("Missing request parameter '{}'", ex.getParameterName());
        return error(resolve("error.badRequest.missingParam", ex.getParameterName()));
    }

    /**
     * A {@code sortBy} naming a field the entity does not have. The list endpoints build their {@code Sort}
     * straight from that query parameter, so a stale bookmark or a hand-edited URL reaches Spring Data with
     * an unknown property — the caller's mistake, not a fault, so 400 rather than the 500 it produced
     * before this handler existed.
     */
    @ExceptionHandler(org.springframework.data.core.PropertyReferenceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleUnknownSortProperty(
            org.springframework.data.core.PropertyReferenceException ex) {
        log.debug("Unknown sort property '{}': {}", ex.getPropertyName(), ex.getMessage());
        return error(resolve("error.badRequest.sortField", ex.getPropertyName()));
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

    /**
     * Too many attempts at a throttled endpoint. 429 is the honest status and lets a client tell a
     * lockout apart from a rejected credential, which a 400 would hide.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> handleTooManyRequests(TooManyRequestsException ex) {
        return error(resolve(ex.getMessageKey(), ex.getArgs()));
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
     * Turns raw database constraint failures into a friendly response instead of a 500 + stack trace.
     * Distinguished by SQLSTATE, which is standard SQL:
     *
     * <ul>
     *   <li>{@code 22001} — a value was longer than its column. This is the <em>caller's</em> input being
     *       too long, not a clash with existing data, so it is a <b>400</b>. It used to fall into the
     *       generic branch and be reported as "this action conflicts with existing data", which described
     *       the wrong problem entirely and gave the user nothing to act on.</li>
     *   <li>{@code 23503} — foreign key: the record is still referenced (409).</li>
     *   <li>{@code 23505} — unique: that value already exists (409).</li>
     *   <li>{@code 23502} — not null: a required value was missing. Also the caller's input (400).</li>
     * </ul>
     *
     * <p>These are a safety net, not the primary defence: a field with a proper {@code @Size}/{@code @NotNull}
     * is rejected by bean validation first, with a message that can name the field. This branch is what
     * stops the ones that slip through from being described as something they are not.</p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String sqlState = sqlStateOf(ex);
        log.debug("Data integrity violation, SQLSTATE {}: {}", sqlState, ex.getMessage());
        return switch (sqlState == null ? "" : sqlState) {
            case "22001" -> ResponseEntity.badRequest().body(error(resolve("error.validation.tooLong")));
            case "23502" -> ResponseEntity.badRequest().body(error(resolve("error.validation.required")));
            case "23503" -> ResponseEntity.status(HttpStatus.CONFLICT).body(error(resolve("error.conflict.foreignKey")));
            case "23505" -> ResponseEntity.status(HttpStatus.CONFLICT).body(error(resolve("error.conflict.unique")));
            default -> ResponseEntity.status(HttpStatus.CONFLICT).body(error(resolve("error.conflict.generic")));
        };
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
