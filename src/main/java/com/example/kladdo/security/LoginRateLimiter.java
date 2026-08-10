package com.example.kladdo.security;

import com.example.kladdo.exception.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throttles repeated failed attempts against the unauthenticated auth endpoints (login and
 * forgot-password), so a valid email address cannot be brute-forced.
 *
 * <p>Two independent counters are kept per attempt:</p>
 * <ul>
 *   <li><b>per account</b> — the targeted email. This is the counter that actually stops credential
 *       brute-forcing, and it cannot be evaded by changing source address.</li>
 *   <li><b>per IP</b> — a looser cap that catches one source spraying many different accounts. It is
 *       deliberately more permissive because offices and mobile carriers share addresses via NAT.</li>
 * </ul>
 *
 * <p>State is in-memory, which suits the current single-instance deployment. Running more than one
 * instance would give each its own counters, multiplying the effective limit by the instance count —
 * move the counters to a shared store (Redis) before scaling out.</p>
 *
 * <p>Counters are keyed on a fixed window: the first failure starts the window, and exceeding the limit
 * inside it blocks further attempts until the lockout expires. A successful login clears the account's
 * counter but <b>not</b> the IP's — otherwise an attacker could reset their IP budget at will simply by
 * signing in to an account they own.</p>
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /**
     * Safety valve so a sustained attack on many distinct emails cannot grow the map without bound.
     * On breach, expired entries are purged; if that frees nothing the map is cleared outright, which
     * briefly forgives live counters but is strictly better than exhausting heap.
     */
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final int maxPerAccount;
    private final int maxPerIp;
    private final Duration window;
    private final Duration lockout;
    private final Clock clock;

    @Autowired
    public LoginRateLimiter(
            @Value("${app.auth.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.auth.rate-limit.max-attempts-per-account:5}") int maxPerAccount,
            @Value("${app.auth.rate-limit.max-attempts-per-ip:20}") int maxPerIp,
            @Value("${app.auth.rate-limit.window-minutes:15}") int windowMinutes,
            @Value("${app.auth.rate-limit.lockout-minutes:15}") int lockoutMinutes) {
        this(enabled, maxPerAccount, maxPerIp, windowMinutes, lockoutMinutes, Clock.systemUTC());
    }

    /** Test seam: lets a fixed/advanceable clock drive window and lockout expiry deterministically. */
    LoginRateLimiter(boolean enabled, int maxPerAccount, int maxPerIp,
                     int windowMinutes, int lockoutMinutes, Clock clock) {
        this.enabled = enabled;
        this.maxPerAccount = maxPerAccount;
        this.maxPerIp = maxPerIp;
        this.window = Duration.ofMinutes(windowMinutes);
        this.lockout = Duration.ofMinutes(lockoutMinutes);
        this.clock = clock;
    }

    /**
     * Rejects the attempt with a 429 if either counter is currently locked out. Call before doing any
     * password work, so a locked-out caller costs nothing to turn away.
     */
    public void checkAllowed(String ip, String email) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        rejectIfBlocked(accountKey(email), now);
        rejectIfBlocked(ipKey(ip), now);
    }

    /** Records one failed attempt against both the account and the source address. */
    public void recordFailure(String ip, String email) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        evictIfOversized(now);
        bump(accountKey(email), maxPerAccount, now, "account " + normalise(email));
        bump(ipKey(ip), maxPerIp, now, "ip " + ip);
    }

    /**
     * Clears the account counter after a genuine sign-in. The IP counter is intentionally left alone —
     * see the class comment.
     */
    public void recordSuccess(String email) {
        if (!enabled) {
            return;
        }
        attempts.remove(accountKey(email));
    }

    /**
     * Best-effort client address. Prefers the left-most {@code X-Forwarded-For} entry, since in
     * production the app sits behind Render's proxy and {@code getRemoteAddr()} would otherwise report
     * the proxy for every caller.
     *
     * <p>A client can of course forge that header, so the per-IP counter is a secondary control only.
     * The per-account counter is the one that cannot be evaded, and it is what actually protects a
     * password from being guessed.</p>
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private void rejectIfBlocked(String key, Instant now) {
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return;
        }
        Instant blockedUntil;
        synchronized (attempt) {
            blockedUntil = attempt.blockedUntil;
        }
        if (blockedUntil != null && now.isBefore(blockedUntil)) {
            // Round up so "0 minutes left" is never shown to someone who still has to wait.
            long minutesLeft = Math.max(1, (long) Math.ceil(Duration.between(now, blockedUntil).toSeconds() / 60.0));
            throw new TooManyRequestsException("error.auth.tooManyAttempts", minutesLeft);
        }
    }

    private void bump(String key, int max, Instant now, String description) {
        Attempt attempt = attempts.computeIfAbsent(key, k -> new Attempt(now));
        synchronized (attempt) {
            // A lapsed window (or an expired lockout) starts counting again from scratch.
            if (attempt.blockedUntil != null && !now.isBefore(attempt.blockedUntil)) {
                attempt.reset(now);
            } else if (now.isAfter(attempt.windowStart.plus(window))) {
                attempt.reset(now);
            }
            attempt.failures++;
            if (attempt.failures >= max && attempt.blockedUntil == null) {
                attempt.blockedUntil = now.plus(lockout);
                log.warn("Rate limit tripped for {} after {} failed attempts; locked out for {} minutes",
                        description, attempt.failures, lockout.toMinutes());
            }
        }
    }

    private void evictIfOversized(Instant now) {
        if (attempts.size() < MAX_TRACKED_KEYS) {
            return;
        }
        attempts.values().removeIf(attempt -> {
            synchronized (attempt) {
                Instant expiry = attempt.blockedUntil != null
                        ? attempt.blockedUntil
                        : attempt.windowStart.plus(window);
                return now.isAfter(expiry);
            }
        });
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            log.warn("Login rate-limit map still at {} entries after purge; clearing it", attempts.size());
            attempts.clear();
        }
    }

    private static String accountKey(String email) {
        return "account:" + normalise(email);
    }

    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }

    /** Lower-cased and trimmed, so casing variants of one address share a counter. */
    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Mutable counter guarded by its own monitor; short critical sections, no cross-key locking. */
    private static final class Attempt {
        private int failures;
        private Instant windowStart;
        private Instant blockedUntil;

        private Attempt(Instant start) {
            this.windowStart = start;
        }

        private void reset(Instant now) {
            failures = 0;
            windowStart = now;
            blockedUntil = null;
        }
    }
}
