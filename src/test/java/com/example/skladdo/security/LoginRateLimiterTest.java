package com.example.skladdo.security;

import com.example.skladdo.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the login/forgot-password brute-force throttle. Pure logic driven by a movable clock -
 * no Spring context and no database, so these run instantly without paying for a Testcontainers Postgres.
 */
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String EMAIL = "victim@example.com";

    /** A clock the test advances by hand, so window and lockout expiry are exercised without sleeping. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T09:00:00Z");

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        void advanceMinutes(long minutes) { now = now.plus(Duration.ofMinutes(minutes)); }
    }

    private final MovableClock clock = new MovableClock();

    /** 3 attempts per account, 5 per IP, 15-minute window, 15-minute lockout. */
    private LoginRateLimiter limiter() {
        return new LoginRateLimiter(true, 3, 5, 15, 15, clock);
    }

    @Test
    void allowsAttemptsBelowTheAccountLimit() {
        LoginRateLimiter limiter = limiter();

        limiter.recordFailure(IP, EMAIL);
        limiter.recordFailure(IP, EMAIL);

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void blocksTheAccountOnceTheLimitIsReached() {
        LoginRateLimiter limiter = limiter();

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(IP, EMAIL);
        }

        assertThatThrownBy(() -> limiter.checkAllowed(IP, EMAIL))
                .isInstanceOf(TooManyRequestsException.class)
                .satisfies(e -> {
                    TooManyRequestsException ex = (TooManyRequestsException) e;
                    assertThat(ex.getMessageKey()).isEqualTo("error.auth.tooManyAttempts");
                    assertThat(ex.getArgs()).containsExactly(15L);
                });
    }

    /** The account counter must follow the email, not the source address. */
    @Test
    void blockedAccountStaysBlockedFromADifferentAddress() {
        LoginRateLimiter limiter = limiter();

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("198.51.100." + i, EMAIL);
        }

        assertThatThrownBy(() -> limiter.checkAllowed("198.51.100.250", EMAIL))
                .isInstanceOf(TooManyRequestsException.class);
    }

    /** Spraying distinct accounts from one address is caught by the looser IP counter. */
    @Test
    void blocksTheAddressAfterSprayingManyAccounts() {
        LoginRateLimiter limiter = limiter();

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(IP, "user" + i + "@example.com");
        }

        assertThatThrownBy(() -> limiter.checkAllowed(IP, "never-tried@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void differentAccountFromACleanAddressIsUnaffected() {
        LoginRateLimiter limiter = limiter();

        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(IP, EMAIL);
        }

        assertThatCode(() -> limiter.checkAllowed("192.0.2.1", "someone-else@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void successClearsTheAccountCounter() {
        LoginRateLimiter limiter = limiter();
        limiter.recordFailure(IP, EMAIL);
        limiter.recordFailure(IP, EMAIL);

        limiter.recordSuccess(EMAIL);
        limiter.recordFailure(IP, EMAIL);
        limiter.recordFailure(IP, EMAIL);

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    /**
     * Signing in successfully must NOT refund the IP budget - otherwise an attacker resets it at will
     * using an account they own, and the per-address control becomes decorative.
     */
    @Test
    void successDoesNotClearTheAddressCounter() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(IP, "user" + i + "@example.com");
        }

        limiter.recordSuccess("attacker-own-account@example.com");

        assertThatThrownBy(() -> limiter.checkAllowed(IP, "attacker-own-account@example.com"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void lockoutExpiresAfterTheConfiguredTime() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(IP, EMAIL);
        }
        assertThatThrownBy(() -> limiter.checkAllowed(IP, EMAIL)).isInstanceOf(TooManyRequestsException.class);

        clock.advanceMinutes(16);

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void reportsWholeMinutesRemainingAndNeverZero() {
        LoginRateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(IP, EMAIL);
        }

        clock.advanceMinutes(10);
        assertThatThrownBy(() -> limiter.checkAllowed(IP, EMAIL))
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getArgs()).containsExactly(5L));

        // 30 seconds left must round up to 1, not down to a misleading 0.
        clock.advanceMinutes(4);
        assertThatThrownBy(() -> limiter.checkAllowed(IP, EMAIL))
                .satisfies(e -> assertThat(((TooManyRequestsException) e).getArgs()).containsExactly(1L));
    }

    /** Failures spread thinly enough never accumulate into a lockout. */
    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        LoginRateLimiter limiter = limiter();

        for (int i = 0; i < 6; i++) {
            limiter.recordFailure(IP, EMAIL);
            clock.advanceMinutes(16);
            limiter.checkAllowed(IP, EMAIL);
        }

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void emailCasingAndPaddingShareOneCounter() {
        LoginRateLimiter limiter = limiter();

        limiter.recordFailure(IP, "Victim@Example.com");
        limiter.recordFailure(IP, "  victim@example.com  ");
        limiter.recordFailure(IP, "VICTIM@EXAMPLE.COM");

        assertThatThrownBy(() -> limiter.checkAllowed(IP, EMAIL))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void disabledLimiterNeverBlocks() {
        LoginRateLimiter limiter = new LoginRateLimiter(false, 3, 5, 15, 15, clock);

        for (int i = 0; i < 50; i++) {
            limiter.recordFailure(IP, EMAIL);
        }

        assertThatCode(() -> limiter.checkAllowed(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void clientIpPrefersTheLeftmostForwardedForEntry() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.9, 70.41.3.18, 150.172.238.178");

        assertThat(LoginRateLimiter.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void clientIpFallsBackToRemoteAddressWhenNotProxied() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.0.2.55");

        assertThat(LoginRateLimiter.clientIp(request)).isEqualTo("192.0.2.55");
    }

    @Test
    void clientIpIgnoresABlankForwardedForHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("192.0.2.55");

        assertThat(LoginRateLimiter.clientIp(request)).isEqualTo("192.0.2.55");
    }
}
