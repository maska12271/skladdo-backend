package com.example.kladdo.controller;

import com.example.kladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The API's error contract: a caller's mistake must be a 4xx that says what to fix, and only a genuine
 * server fault may be a 5xx.
 *
 * <p>This class exists because the 2026-08-06 pass found the boundary drawn in the wrong place — malformed
 * JSON, an unknown enum value, a mistyped path variable and an over-long string were all reported as
 * <em>500 "something went wrong on our end… contact support"</em>. Beyond misleading users, that buries
 * real faults in monitoring noise, which is why these are worth pinning rather than leaving to a manual
 * pass to rediscover.</p>
 */
class ErrorContractIntegrationTest extends ApiTestBase {

    // -------------------------------------------------------------------------------------------------
    // unreadable request bodies (F-008)
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a body that cannot be parsed is 400, never 500")
    void unreadableBodiesAre400() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = createCategory(owner, "Contract Category");
        long manufacturerId = createManufacturer(owner, "Contract Manufacturer");
        String relations = """
                           "manufacturer":{"id":%d},"category":{"id":%d}
                           """.formatted(manufacturerId, categoryId);

        assertThat(postStatus(owner, "/api/products", "{\"name\":"))
                .as("syntactically broken JSON").isEqualTo(400);
        assertThat(postStatus(owner, "/api/products", "{\"name\":\"X\",\"price\":\"not-a-number\"," + relations + "}"))
                .as("string where a number is expected").isEqualTo(400);
        assertThat(postStatus(owner, "/api/products", "{\"name\":\"X\",\"price\":10,\"warehouseMethod\":\"NOPE\"," + relations + "}"))
                .as("value outside the enum").isEqualTo(400);
        assertThat(postStatus(owner, "/api/products", ""))
                .as("empty body").isEqualTo(400);
        assertThat(postStatus(owner, "/api/users", "{\"email\":\"e@test.local\",\"fullName\":\"X\",\"role\":\"SUPERUSER\"}"))
                .as("unknown role").isEqualTo(400);
    }

    @Test
    @DisplayName("a path variable of the wrong type is 400, never 500")
    void pathTypeMismatchIs400() throws Exception {
        Tenant owner = newBusiness();

        assertThat(statusOfGet("/api/products/not-a-number", owner)).isEqualTo(400);
    }

    /**
     * Regression guard for F-012. {@code MissingServletRequestParameterException} implements Spring's
     * {@link org.springframework.web.ErrorResponse}, so the catch-all rethrows it to preserve the 400 -
     * but with no handler claiming it, that rethrow fell through to Spring Boot's own default error body
     * instead of this app's translated {@code {"error": ...}} contract, which in dev includes a full stack
     * trace ({@code server.error.include-stacktrace=ALWAYS} whenever {@code spring-boot-devtools} is on the
     * classpath).
     */
    @Test
    @DisplayName("a missing required query parameter is 400 with the app's own translated body, not Spring's raw error page")
    void missingRequiredParameterIsAppError() throws Exception {
        Tenant owner = newBusiness();
        long warehouseId = readJson(mvc.perform(authed(post("/api/warehouses"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"Contract Depot"}
                         """)).andReturn()).path("id").asLong();

        JsonNode body = readJson(mvc.perform(
                authed(get("/api/warehouses/" + warehouseId + "/movements"), owner)).andReturn());

        assertThat(statusOfGet("/api/warehouses/" + warehouseId + "/movements", owner)).isEqualTo(400);
        assertThat(body.has("trace")).as("no stack trace leaks to the client").isFalse();
        assertThat(body.has("error")).as("app's own error shape").isTrue();
        assertThat(body.path("error").asText()).contains("productId");
    }

    /**
     * {@code sortBy} comes straight off the query string and is handed to Spring Data unchecked, so a stale
     * bookmark naming a renamed column reaches it as an unknown property. That is the caller's problem to
     * fix, not a fault to page someone about.
     */
    @Test
    @DisplayName("sorting by a field that does not exist is 400 and names the field")
    void unknownSortFieldIs400() throws Exception {
        Tenant owner = newBusiness();

        for (String entity : new String[]{"products", "clients", "manufacturers"}) {
            JsonNode body = readJson(mvc.perform(
                    authed(get("/api/" + entity + "?sortBy=bogusField&sortDir=asc"), owner)).andReturn());
            assertThat(statusOfGet("/api/" + entity + "?sortBy=bogusField&sortDir=asc", owner))
                    .as(entity).isEqualTo(400);
            assertThat(body.path("error").asText()).as(entity + " names the offending field").contains("bogusField");
        }

        // Sorting itself must still work, in both directions.
        JsonNode ascending = readJson(mvc.perform(
                authed(get("/api/products?size=5&sortBy=name&sortDir=asc"), owner)).andReturn());
        JsonNode descending = readJson(mvc.perform(
                authed(get("/api/products?size=5&sortBy=name&sortDir=desc"), owner)).andReturn());
        assertThat(ascending.path("content")).isNotNull();
        assertThat(descending.path("content")).isNotNull();
    }

    /**
     * Regression guard for N-007. These flags are documented as defaulting when omitted, but as Java
     * primitives there was no value to bind when the property was absent, so a valid body without them was
     * rejected wholesale as unreadable. Wrapper types make "omitted" expressible.
     *
     * <p>Deliberately narrow: {@code CompanySettingsDto} and {@code ModulePermissionDto} are still strict,
     * because both are whole-object APIs where quietly defaulting an absent boolean to {@code false} would
     * silently switch something off the caller never mentioned.</p>
     */
    @Test
    @DisplayName("optional boolean flags may be omitted and take their documented default")
    void optionalFlagsMayBeOmitted() throws Exception {
        Tenant owner = newBusiness();

        JsonNode template = readJson(mvc.perform(authed(post("/api/email-templates"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"No active flag","subject":"s","body":"b"}
                         """)).andReturn());
        assertThat(template.path("id").asLong()).as("template was created").isPositive();
        assertThat(template.path("active").asBoolean()).as("defaults to active").isTrue();

        JsonNode rate = readJson(mvc.perform(authed(post("/api/settings/tax-rates"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"No flags","percentage":20}
                         """)).andReturn());
        assertThat(rate.path("id").asLong()).as("tax rate was created").isPositive();
        assertThat(rate.path("active").asBoolean()).as("defaults to active").isTrue();

        // Explicitly supplying them still works and still wins.
        assertThat(postStatus(owner, "/api/email-templates",
                "{\"name\":\"Explicit\",\"subject\":\"s\",\"body\":\"b\",\"active\":false}")).isEqualTo(200);
    }

    // -------------------------------------------------------------------------------------------------
    // validation reaching the database (F-004, F-009)
    // -------------------------------------------------------------------------------------------------

    /**
     * Over-length input is the caller's mistake, so it must be a 400 saying the value is too long — not the
     * 409 "conflicts with existing data" it used to produce, which described a duplicate-key clash and left
     * the user with nothing to act on.
     */
    @Test
    @DisplayName("a value longer than its column is 400 and says it is too long")
    void overLongValuesAre400() throws Exception {
        Tenant owner = newBusiness();
        String tooLong = "A".repeat(300);

        JsonNode body = readJson(mvc.perform(authed(post("/api/clients"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s"}
                         """.formatted(tooLong))).andReturn());

        assertThat(postStatus(owner, "/api/clients", "{\"name\":\"" + tooLong + "\"}")).isEqualTo(400);
        assertThat(body.path("error").asText().toLowerCase()).contains("too long");
        // The old wording described the wrong problem entirely.
        assertThat(body.path("error").asText().toLowerCase()).doesNotContain("conflict");
    }

    @Test
    @DisplayName("the exact length boundary is respected")
    void lengthBoundaryIsExact() throws Exception {
        Tenant owner = newBusiness();

        assertThat(postStatus(owner, "/api/clients", "{\"name\":\"" + "A".repeat(255) + "\"}"))
                .as("255 characters fits").isEqualTo(200);
        assertThat(postStatus(owner, "/api/clients", "{\"name\":\"" + "A".repeat(256) + "\"}"))
                .as("256 characters does not").isEqualTo(400);
        assertThat(postStatus(owner, "/api/clients", "{\"name\":\"Notes A\",\"notes\":\"" + "A".repeat(2000) + "\"}"))
                .as("notes column holds 2000").isEqualTo(200);
        assertThat(postStatus(owner, "/api/clients", "{\"name\":\"Notes B\",\"notes\":\"" + "A".repeat(2001) + "\"}"))
                .as("2001 is rejected").isEqualTo(400);
    }

    @Test
    @DisplayName("a missing required relation is 400, not an NPE or a database conflict")
    void missingRequiredRelationIs400() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = createCategory(owner, "Relation Category");
        long manufacturerId = createManufacturer(owner, "Relation Manufacturer");

        assertThat(postStatus(owner, "/api/products",
                "{\"name\":\"X\",\"price\":10,\"manufacturer\":{\"id\":" + manufacturerId + "}}"))
                .as("no category").isEqualTo(400);
        assertThat(postStatus(owner, "/api/products",
                "{\"name\":\"X\",\"price\":10,\"category\":{\"id\":" + categoryId + "}}"))
                .as("no manufacturer").isEqualTo(400);
        assertThat(postStatus(owner, "/api/products", "{\"name\":\"X\",\"price\":10}"))
                .as("neither").isEqualTo(400);
    }

    // -------------------------------------------------------------------------------------------------
    // the statuses that must NOT have changed
    // -------------------------------------------------------------------------------------------------

    /**
     * The fix narrowed what counts as a conflict, so the cases that genuinely are one need holding down —
     * otherwise a later tidy-up could quietly turn every constraint failure into a 400.
     */
    @Test
    @DisplayName("a genuine uniqueness clash is still 409")
    void duplicateValueIsStillConflict() throws Exception {
        Tenant owner = newBusiness();
        createCategory(owner, "Unique Name");

        assertThat(postStatus(owner, "/api/categories", "{\"name\":\"Unique Name\"}")).isEqualTo(409);
    }

    @Test
    @DisplayName("exceptions carrying their own status keep it")
    void springsOwnStatusesSurvive() throws Exception {
        Tenant owner = newBusiness();

        assertThat(statusOf(delete("/api/currencies"), owner)).as("wrong method").isEqualTo(405);
        assertThat(statusOfGet("/api/no-such-endpoint", owner)).as("unmapped path").isEqualTo(404);
        assertThat(statusOf(post("/api/products").contentType(MediaType.TEXT_PLAIN).content("nope"), owner))
                .as("unsupported media type").isEqualTo(415);
    }

    @Test
    @DisplayName("a missing record is still 404 and a denied one still 403")
    void notFoundAndForbiddenAreUnchanged() throws Exception {
        Tenant owner = newBusiness();
        Tenant restricted = inviteUser(owner, "USER");
        setPermissions(owner, restricted.userId()); // nothing granted

        assertThat(statusOfGet("/api/products/999999", owner)).isEqualTo(404);
        assertThat(statusOfGet("/api/products?size=1", restricted)).isEqualTo(403);
    }

    @Test
    @DisplayName("error messages are translated, not raw keys")
    void errorMessagesAreTranslated() throws Exception {
        Tenant owner = newBusiness();
        String tooLong = "A".repeat(300);

        for (String language : new String[]{"en", "et", "ru"}) {
            JsonNode body = readJson(mvc.perform(authed(post("/api/clients"), owner)
                    .header("Accept-Language", language)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                             {"name":"%s"}
                             """.formatted(tooLong))).andReturn());

            String message = body.path("error").asText();
            assertThat(message).as(language + " message").isNotBlank();
            // A missing bundle entry falls back to the key itself, which is the failure worth catching.
            assertThat(message).as(language + " must not be a raw key").doesNotContain("error.validation");
        }
    }

    // -------------------------------------------------------------------------------------------------

    private int postStatus(Tenant tenant, String path, String body) throws Exception {
        return mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getStatus();
    }

    private long createCategory(Tenant tenant, String name) throws Exception {
        return readJson(mvc.perform(authed(post("/api/categories"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s"}
                         """.formatted(name))).andReturn()).path("id").asLong();
    }

    private long createManufacturer(Tenant tenant, String name) throws Exception {
        return readJson(mvc.perform(authed(post("/api/manufacturers"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s"}
                         """.formatted(name))).andReturn()).path("id").asLong();
    }
}
