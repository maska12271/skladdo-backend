package com.example.skladdo.controller;

import com.example.skladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end coverage of sign-in, token handling and self-service signup, through the real HTTP stack.
 *
 * <p>These pin behaviour that was verified by hand during the 2026-08-06 test pass — in particular the JWT
 * forgery rejections and the server-side plan derivation, both of which are security properties that would
 * be easy to regress silently.</p>
 */
class AuthIntegrationTest extends ApiTestBase {

    // -------------------------------------------------------------------------------------------------
    // sign-in
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("valid credentials return a token and the caller's profile")
    void loginSucceeds() throws Exception {
        Tenant owner = newBusiness();

        JsonNode body = readJson(mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s","password":"%s"}
                         """.formatted(owner.email(), owner.password()))).andReturn());

        assertThat(body.path("token").asText()).isNotBlank();
        assertThat(body.path("user").path("email").asText()).isEqualTo(owner.email());
        assertThat(body.path("user").path("role").asText()).isEqualTo("OWNER");
        assertThat(body.path("user").path("companyType").asText()).isEqualTo("BUSINESS");
    }

    @Test
    @DisplayName("unknown email and wrong password are reported distinctly")
    void loginFailuresAreDistinguishable() throws Exception {
        Tenant owner = newBusiness();

        String unknown = errorOf(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"no-such-account@test.local","password":"whatever"}
                         """));
        String wrongPassword = errorOf(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s","password":"definitely-wrong"}
                         """.formatted(owner.email())));

        // The two messages differ by design (see N-001) — asserted so the trade-off stays a decision
        // rather than something that drifts unnoticed in either direction.
        assertThat(unknown).isNotEqualTo(wrongPassword);
        assertThat(unknown).contains("no account");
        assertThat(wrongPassword).contains("password");
    }

    @Test
    @DisplayName("an invited user cannot sign in until they have set a password")
    void loginBlockedWhileSetupPending() throws Exception {
        Tenant owner = newBusiness();
        JsonNode created = readJson(mvc.perform(authed(post("/api/users"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"pending-user@test.local","fullName":"Pending","role":"USER"}
                         """)).andReturn());
        assertThat(created.path("user").path("passwordSetupPending").asBoolean()).isTrue();

        int status = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"pending-user@test.local","password":"anything"}
                         """)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
    }

    // -------------------------------------------------------------------------------------------------
    // token handling
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a valid token identifies the caller")
    void meReturnsProfile() throws Exception {
        Tenant owner = newBusiness();

        JsonNode me = readJson(mvc.perform(authed(get("/api/auth/me"), owner)).andReturn());

        assertThat(me.path("email").asText()).isEqualTo(owner.email());
        assertThat(me.path("companyId").asLong()).isEqualTo(owner.companyId());
    }

    @Test
    @DisplayName("requests without a token are rejected")
    void noTokenIsUnauthorised() throws Exception {
        assertThat(mvc.perform(get("/api/auth/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /**
     * The forgery cases that matter. The tampered-claim one is the important assertion: the tenant a
     * request runs in comes from a signed claim, so if the signature stopped being checked, one company
     * could read another's data by editing a number.
     */
    @Test
    @DisplayName("forged tokens are rejected: garbage, stripped signature, alg=none, tampered claims")
    void forgedTokensAreRejected() throws Exception {
        Tenant owner = newBusiness();
        String[] parts = owner.token().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

        String noneHeader = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String otherCompany = payload
                .replace("\"companyId\":" + owner.companyId(), "\"companyId\":" + (owner.companyId() + 1))
                .replace("\"activeCompanyId\":" + owner.companyId(), "\"activeCompanyId\":" + (owner.companyId() + 1));

        assertThat(statusWithRawToken("not.a.jwt")).as("garbage").isEqualTo(401);
        assertThat(statusWithRawToken(parts[0] + "." + parts[1] + ".")).as("signature stripped").isEqualTo(401);
        assertThat(statusWithRawToken(noneHeader + "." + parts[1] + ".")).as("alg=none").isEqualTo(401);
        assertThat(statusWithRawToken(parts[0] + "." + b64(otherCompany) + "." + parts[2]))
                .as("tampered companyId keeps the original signature").isEqualTo(401);
    }

    // -------------------------------------------------------------------------------------------------
    // self-service signup
    // -------------------------------------------------------------------------------------------------

    /**
     * A warehouse signup must land on the free tier no matter what the client asks for. The plan is derived
     * from the account type server-side; trusting the request here would be a free upgrade for anyone
     * willing to edit a JSON body.
     */
    @Test
    @DisplayName("a WAREHOUSE signup claiming ENTERPRISE still gets the free warehouse plan")
    void warehouseSignupCannotClaimAPaidPlan() throws Exception {
        JsonNode registered = readJson(mvc.perform(post("/api/public/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"companyName":"Sneaky Warehouse","fullName":"WH","email":"sneaky-wh@test.local",
                          "password":"testpass123","accountType":"WAREHOUSE","plan":"ENTERPRISE"}
                         """)).andReturn());

        Tenant warehouse = tenantFrom(registered, "sneaky-wh@test.local", "testpass123");

        JsonNode subscription = readJson(mvc.perform(authed(get("/api/subscription"), warehouse)).andReturn());

        assertThat(registered.path("user").path("companyType").asText()).isEqualTo("WAREHOUSE");
        assertThat(subscription.path("plan").asText()).isEqualTo("WAREHOUSE");
        assertThat(subscription.path("monthlyPrice").asInt()).isZero();
        // A free account is never offered paid tiers or add-ons to buy.
        assertThat(subscription.path("plans")).isEmpty();
        assertThat(subscription.path("addons")).isEmpty();
    }

    @Test
    @DisplayName("a BUSINESS signup cannot select the non-selectable free warehouse plan")
    void businessCannotSelectTheWarehousePlan() throws Exception {
        int status = mvc.perform(post("/api/public/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"companyName":"Freeloader","fullName":"B","email":"freeloader@test.local",
                          "password":"testpass123","accountType":"BUSINESS","plan":"WAREHOUSE"}
                         """)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
    }

    @Test
    @DisplayName("signup rejects a missing plan, an unknown plan, and an unknown account type")
    void signupValidatesPlanAndAccountType() throws Exception {
        assertThat(registerStatus("""
                {"companyName":"NoPlan","fullName":"B","email":"noplan@test.local",
                 "password":"testpass123","accountType":"BUSINESS"}
                """)).as("missing plan").isEqualTo(400);
        assertThat(registerStatus("""
                {"companyName":"Gold","fullName":"B","email":"gold@test.local",
                 "password":"testpass123","accountType":"BUSINESS","plan":"GOLD"}
                """)).as("unknown plan").isEqualTo(400);
        assertThat(registerStatus("""
                {"companyName":"Hacker","fullName":"B","email":"hacker@test.local",
                 "password":"testpass123","accountType":"HACKER","plan":"STARTER"}
                """)).as("unknown account type").isEqualTo(400);
    }

    @Test
    @DisplayName("signup rejects a duplicate email and a too-short password")
    void signupRejectsDuplicateEmailAndWeakPassword() throws Exception {
        Tenant existing = newBusiness();

        assertThat(registerStatus("""
                {"companyName":"Dup","fullName":"B","email":"%s",
                 "password":"testpass123","accountType":"BUSINESS","plan":"STARTER"}
                """.formatted(existing.email()))).as("duplicate email").isEqualTo(400);
        assertThat(registerStatus("""
                {"companyName":"Short","fullName":"B","email":"shortpw@test.local",
                 "password":"abc12","accountType":"BUSINESS","plan":"STARTER"}
                """)).as("5-character password").isEqualTo(400);
    }

    /**
     * Pins the exact minimum rather than just "something short fails" — the policy is enforced at three
     * independent points (signup, reset, change), so the boundary is the thing worth holding still.
     * Raised from 6 to 8 on 2026-08-10 (finding N-002); see {@code PasswordPolicy}.
     */
    @Test
    @DisplayName("the password minimum is 8 characters, exactly")
    void passwordMinimumIsEightCharacters() throws Exception {
        assertThat(registerStatus("""
                {"companyName":"Seven","fullName":"B","email":"pw7@test.local",
                 "password":"abcd123","accountType":"BUSINESS","plan":"STARTER"}
                """)).as("7 characters is rejected").isEqualTo(400);
        assertThat(registerStatus("""
                {"companyName":"Eight","fullName":"B","email":"pw8@test.local",
                 "password":"abcd1234","accountType":"BUSINESS","plan":"STARTER"}
                """)).as("8 characters is accepted").isEqualTo(200);
    }

    // -------------------------------------------------------------------------------------------------
    // password setup / reset
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a setup token works once, then is dead")
    void passwordSetupTokenIsSingleUse() throws Exception {
        Tenant owner = newBusiness();
        JsonNode created = readJson(mvc.perform(authed(post("/api/users"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"once@test.local","fullName":"Once","role":"USER"}
                         """)).andReturn());
        String link = created.path("setupLink").asText();
        String token = link.substring(link.indexOf("token=") + "token=".length());

        int first = resetStatus(token, "brandnew123");
        int second = resetStatus(token, "another12345");

        assertThat(first).as("first redemption").isEqualTo(200);
        assertThat(second).as("reuse").isEqualTo(400);
        assertThat(login("once@test.local", "brandnew123")).isNotBlank();

        // A spent token must not disclose which account it belonged to.
        JsonNode info = readJson(mvc.perform(get("/api/public/password/reset").param("token", token)).andReturn());
        assertThat(info.path("valid").asBoolean()).isFalse();
        assertThat(info.path("email").asText("")).isEmpty();
    }

    @Test
    @DisplayName("bogus reset tokens report invalid without leaking an account")
    void bogusResetTokensLeakNothing() throws Exception {
        for (String token : new String[]{"nonsense", "../../etc/passwd", "%00"}) {
            JsonNode info = readJson(mvc.perform(get("/api/public/password/reset").param("token", token)).andReturn());
            assertThat(info.path("valid").asBoolean()).as(token).isFalse();
            assertThat(info.path("email").asText("")).as(token).isEmpty();
        }
    }

    /**
     * Regression guard for F-005: the endpoint must say whether mail actually went out, and must never
     * hand back the reset link — it is unauthenticated, so returning the link would let anyone take over
     * any account by naming it.
     */
    @Test
    @DisplayName("forgot-password reports emailSent and never returns the link")
    void forgotPasswordReportsSendOutcomeWithoutLeakingTheLink() throws Exception {
        Tenant owner = newBusiness();

        JsonNode body = readJson(mvc.perform(post("/api/public/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s"}
                         """.formatted(owner.email()))).andReturn());

        // No SMTP is configured in tests, so nothing can have been sent.
        assertThat(body.path("emailSent").asBoolean()).isFalse();
        assertThat(body.has("setupLink")).as("must not expose the reset link").isFalse();
        assertThat(body.properties()).hasSize(1);
    }

    @Test
    @DisplayName("forgot-password rejects an unknown address")
    void forgotPasswordRejectsUnknownAddress() throws Exception {
        int status = mvc.perform(post("/api/public/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"nobody-at-all@test.local"}
                         """)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
    }

    // -------------------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------------------

    private int registerStatus(String body) throws Exception {
        return mvc.perform(post("/api/public/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getStatus();
    }

    private int resetStatus(String token, String password) throws Exception {
        return mvc.perform(post("/api/public/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"token":"%s","password":"%s"}
                         """.formatted(token, password))).andReturn().getResponse().getStatus();
    }

    private int statusWithRawToken(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private String errorOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return readJson(mvc.perform(builder).andReturn()).path("error").asText().toLowerCase();
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
