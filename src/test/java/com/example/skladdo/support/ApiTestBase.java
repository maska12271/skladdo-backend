package com.example.skladdo.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 moved this out of ...boot.test.autoconfigure.web.servlet into the webmvc-test module.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared plumbing for the MockMvc integration tests: a booted application on an in-memory database, plus
 * helpers for the two things every test needs — a company to act in, and a token to act with.
 *
 * <p>Fixtures are built through the <em>public API</em> rather than by writing entities directly. That
 * costs a few extra calls but means the setup exercises the same registration, invitation and
 * password-setup paths a real tenant goes through, so a break in any of them shows up here rather than
 * being papered over by hand-built rows. It also keeps these tests independent of {@code DataInitializer},
 * which is disabled in this profile.</p>
 *
 * <p>Every company gets a unique email prefix from {@link #SEQ}, so tests never collide on the
 * system-wide-unique user email even when the context is shared across classes.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestBase {

    /** Makes every generated email unique within a JVM run. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Points app.storage.s3.* at TestcontainersConfiguration.LOCALSTACK (see its Javadoc for why). */
    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.s3.endpoint-override",
                () -> TestcontainersConfiguration.LOCALSTACK.getEndpoint().toString());
        registry.add("app.storage.s3.region", TestcontainersConfiguration.LOCALSTACK::getRegion);
        registry.add("app.storage.s3.access-key", TestcontainersConfiguration.LOCALSTACK::getAccessKey);
        registry.add("app.storage.s3.secret-key", TestcontainersConfiguration.LOCALSTACK::getSecretKey);
        registry.add("app.storage.s3.path-style-access", () -> "true");
    }

    @Autowired
    protected MockMvc mvc;

    protected final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetNothing() {
        // Intentionally empty: each test creates its own company, so there is no shared state to reset.
        // Kept as a marker so the reason is obvious to the next person rather than looking like an omission.
    }

    // -------------------------------------------------------------------------------------------------
    // fixtures
    // -------------------------------------------------------------------------------------------------

    /**
     * A registered BUSINESS company on the STARTER plan with both add-ons bought, and its OWNER signed in.
     *
     * <p>The add-ons are included so that tests about <em>permissions</em> stay about permissions. Tenders
     * and manufacturer emails are sold separately and their endpoints are closed to a company that has not
     * bought them, so a fixture without them would make every "an owner reaches everything" assertion fail
     * for a reason that has nothing to do with the matrix under test. Use
     * {@link #newBusinessWithoutAddons()} when the entitlement itself is the subject.</p>
     */
    protected Tenant newBusiness() throws Exception {
        return register("BUSINESS", "STARTER", true);
    }

    /** The same, but sold no add-ons - so the tender and manufacturer-email endpoints are closed to it. */
    protected Tenant newBusinessWithoutAddons() throws Exception {
        return register("BUSINESS", "STARTER", false);
    }

    /** A registered WAREHOUSE (3PL) account, with its OWNER signed in. */
    protected Tenant newWarehouseAccount() throws Exception {
        return register(null, null, false);
    }

    private Tenant register(String accountType, String plan, boolean withAddons) throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "it-owner-" + n + "@test.local";
        String addons = withAddons ? ",\"addons\":[\"TENDERS\",\"MANUFACTURER_EMAILS\"]" : "";
        String body = accountType == null
                ? """
                  {"companyName":"IT Warehouse %d","fullName":"IT Owner %d","email":"%s",
                   "password":"testpass123","accountType":"WAREHOUSE"}
                  """.formatted(n, n, email)
                : """
                  {"companyName":"IT Company %d","fullName":"IT Owner %d","email":"%s",
                   "password":"testpass123","accountType":"%s","plan":"%s"%s}
                  """.formatted(n, n, email, accountType, plan, addons);

        JsonNode response = readJson(mvc.perform(post("/api/public/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn());

        return tenantFrom(response, email, "testpass123");
    }

    /**
     * Builds a session from a login/registration response body. Exists so tests never have to construct
     * {@link Tenant} themselves — its canonical constructor is not visible outside this package.
     */
    protected Tenant tenantFrom(JsonNode loginOrRegisterResponse, String email, String password) {
        return new Tenant(
                loginOrRegisterResponse.path("token").asText(),
                loginOrRegisterResponse.path("user").path("companyId").asLong(),
                loginOrRegisterResponse.path("user").path("id").asLong(),
                email,
                password);
    }

    /**
     * Invites a user into {@code owner}'s company and completes their password setup, returning a signed-in
     * session. Goes the long way round on purpose — invite, read the setup link, redeem it, log in — because
     * that is the only way a non-owner account can legitimately come to exist.
     */
    protected Tenant inviteUser(Tenant owner, String role) throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "it-user-" + n + "@test.local";
        String password = "userpass123";

        JsonNode created = readJson(mvc.perform(authed(post("/api/users"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s","fullName":"IT User %d","role":"%s"}
                         """.formatted(email, n, role))).andReturn());

        Long userId = created.path("user").path("id").asLong();
        // No SMTP in tests, so emailSent is false and the copyable link is the only way through - which is
        // exactly the fallback an admin uses when a tenant has not configured mail yet.
        String setupLink = created.path("setupLink").asText();
        String token = setupLink.substring(setupLink.indexOf("token=") + "token=".length());

        mvc.perform(post("/api/public/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"token":"%s","password":"%s"}
                         """.formatted(token, password)));

        return new Tenant(login(email, password), owner.companyId(), userId, email, password);
    }

    /**
     * Replaces a user's module permissions wholesale. Modules not named are left with no access, which is
     * what makes this usable for building a precisely-scoped principal.
     */
    protected void setPermissions(Tenant admin, Long userId, String... grants) throws Exception {
        mvc.perform(authed(put("/api/users/" + userId + "/permissions"), admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[" + String.join(",", grants) + "]}"));
    }

    /** Convenience for a single module's four flags. */
    protected static String grant(String module, boolean view, boolean create, boolean edit, boolean delete) {
        return """
               {"module":"%s","canView":%s,"canCreate":%s,"canEdit":%s,"canDelete":%s}
               """.formatted(module, view, create, edit, delete);
    }

    /**
     * Issues a connection code as {@code client}, redeems it as {@code warehouseAccount}, and switches the
     * latter in - the only legitimate way a {@link com.example.skladdo.model.WarehouseConnection} comes to
     * exist, and every test that needs one wants a working partner session out of it anyway.
     */
    protected Tenant connect(Tenant client, Tenant warehouseAccount) throws Exception {
        String code = readJson(mvc.perform(authed(get("/api/warehouse-partners/code"), client)).andReturn())
                .path("code").asText();

        mvc.perform(authed(post("/api/warehouse-partners/redeem"), warehouseAccount)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"code":"%s"}
                         """.formatted(code)));

        return switchInto(warehouseAccount, client.companyId());
    }

    /** Swaps {@code tenant}'s session into {@code targetCompanyId}, returning a session for the new context. */
    protected Tenant switchInto(Tenant tenant, Long targetCompanyId) throws Exception {
        JsonNode response = readJson(mvc.perform(authed(post("/api/auth/switch-company"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"companyId":%d}
                         """.formatted(targetCompanyId))).andReturn());
        return tenantFrom(response, tenant.email(), tenant.password());
    }

    // -------------------------------------------------------------------------------------------------
    // request helpers
    // -------------------------------------------------------------------------------------------------

    protected String login(String email, String password) throws Exception {
        JsonNode response = readJson(mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"email":"%s","password":"%s"}
                         """.formatted(email, password))).andReturn());
        return response.path("token").asText();
    }

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, Tenant tenant) {
        return builder.header("Authorization", "Bearer " + tenant.token());
    }

    /** Status code of a plain authenticated GET — the workhorse of the permission/isolation matrices. */
    protected int statusOfGet(String path, Tenant tenant) throws Exception {
        return mvc.perform(authed(get(path), tenant)).andReturn().getResponse().getStatus();
    }

    protected int statusOf(MockHttpServletRequestBuilder builder, Tenant tenant) throws Exception {
        return mvc.perform(authed(builder, tenant)).andReturn().getResponse().getStatus();
    }

    protected JsonNode readJson(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    /** A company plus a signed-in session inside it. */
    protected record Tenant(String token, Long companyId, Long userId, String email, String password) {
    }
}
