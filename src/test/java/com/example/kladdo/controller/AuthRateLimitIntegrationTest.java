package com.example.kladdo.controller;

import com.example.kladdo.support.ApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Wiring check for the login throttle. {@link com.example.kladdo.security.LoginRateLimiterTest} already
 * covers the counting algorithm exhaustively against a movable clock; what cannot be proven there is that
 * the limiter is actually reached by a real request, that the exception becomes a 429 rather than leaking
 * as a 500, and that the message is translated. That is what this class asserts.
 *
 * <p>Runs in its own Spring context because the shared test profile disables throttling — other tests make
 * many deliberate failed logins and would lock themselves out.</p>
 *
 * <p>The per-IP cap is set absurdly high on purpose: every MockMvc request originates from the same
 * address, so a realistic IP limit would be consumed by the other tests in this class and turn their
 * failures into confusing cross-test interference. The per-account counter is the one under test here;
 * the per-IP counter has unit coverage.</p>
 */
@TestPropertySource(properties = {
        "app.auth.rate-limit.enabled=true",
        "app.auth.rate-limit.max-attempts-per-account=3",
        "app.auth.rate-limit.max-attempts-per-ip=100000",
        "app.auth.rate-limit.window-minutes=15",
        "app.auth.rate-limit.lockout-minutes=15",
})
class AuthRateLimitIntegrationTest extends ApiTestBase {

    @Test
    @DisplayName("repeated failed logins are throttled with 429 once the account limit is hit")
    void repeatedFailuresAreThrottled() throws Exception {
        Tenant owner = newBusiness();

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(loginStatus(owner.email(), "wrong-" + attempt))
                    .as("attempt " + attempt + " below the limit").isEqualTo(400);
        }

        assertThat(loginStatus(owner.email(), "wrong-4")).as("first blocked attempt").isEqualTo(429);
        // Even the correct password is refused while locked out - otherwise the limit would be trivially
        // bypassable by the very guessing it is meant to stop.
        assertThat(loginStatus(owner.email(), owner.password())).as("correct password during lockout").isEqualTo(429);
    }

    @Test
    @DisplayName("a lockout is scoped to the account it was earned on")
    void lockoutDoesNotAffectOtherAccounts() throws Exception {
        Tenant locked = newBusiness();
        Tenant bystander = newBusiness();
        for (int attempt = 1; attempt <= 4; attempt++) {
            loginStatus(locked.email(), "wrong-" + attempt);
        }
        assertThat(loginStatus(locked.email(), locked.password())).as("locked out").isEqualTo(429);

        assertThat(loginStatus(bystander.email(), bystander.password()))
                .as("an unrelated account still signs in").isEqualTo(200);
    }

    @Test
    @DisplayName("the 429 carries a translated message, not a raw error")
    void throttleMessageIsTranslated() throws Exception {
        Tenant owner = newBusiness();
        for (int attempt = 1; attempt <= 4; attempt++) {
            loginStatus(owner.email(), "wrong-" + attempt);
        }

        String english = errorFor(owner.email(), "en");
        String estonian = errorFor(owner.email(), "et");
        String russian = errorFor(owner.email(), "ru");

        assertThat(english).contains("Too many failed attempts").contains("15 minutes");
        assertThat(estonian).contains("Liiga palju");
        assertThat(russian).contains("Слишком много");
        // A missing bundle entry would fall back to the raw key.
        assertThat(english).doesNotContain("error.auth");
    }

    /**
     * Unknown-email attempts must count too. They are the enumeration half of the attack, so leaving them
     * unthrottled would make the limit pointless for exactly the traffic it exists to stop.
     */
    @Test
    @DisplayName("attempts against an unknown address are throttled as well")
    void unknownAddressesAreThrottledToo() throws Exception {
        String unknown = "never-registered@test.local";

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(loginStatus(unknown, "guess-" + attempt)).as("attempt " + attempt).isEqualTo(400);
        }

        assertThat(loginStatus(unknown, "guess-4")).isEqualTo(429);
    }

    @Test
    @DisplayName("forgot-password shares the same throttle")
    void forgotPasswordIsThrottled() throws Exception {
        String unknown = "forgot-probe@test.local";

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(forgotStatus(unknown)).as("attempt " + attempt).isEqualTo(400);
        }

        assertThat(forgotStatus(unknown)).isEqualTo(429);
    }

    @Test
    @DisplayName("a successful sign-in clears the account's counter")
    void successResetsTheAccountCounter() throws Exception {
        Tenant owner = newBusiness();
        loginStatus(owner.email(), "wrong-1");
        loginStatus(owner.email(), "wrong-2");

        assertThat(loginStatus(owner.email(), owner.password())).as("still under the limit").isEqualTo(200);

        // Counter cleared, so the budget starts again rather than tripping on the next single failure.
        assertThat(loginStatus(owner.email(), "wrong-3")).isEqualTo(400);
        assertThat(loginStatus(owner.email(), "wrong-4")).isEqualTo(400);
        assertThat(loginStatus(owner.email(), owner.password())).as("still usable").isEqualTo(200);
    }

    // -------------------------------------------------------------------------------------------------

    private int loginStatus(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s","password":"%s"}
                         """.formatted(email, password))).andReturn().getResponse().getStatus();
    }

    private int forgotStatus(String email) throws Exception {
        return mvc.perform(post("/api/public/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s"}
                         """.formatted(email))).andReturn().getResponse().getStatus();
    }

    private String errorFor(String email, String language) throws Exception {
        return readJson(mvc.perform(post("/api/auth/login")
                .header("Accept-Language", language)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s","password":"anything"}
                         """.formatted(email))).andReturn()).path("error").asText();
    }
}
