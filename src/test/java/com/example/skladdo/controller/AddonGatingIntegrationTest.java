package com.example.skladdo.controller;

import com.example.skladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tenders and manufacturer emails are sold separately, and a company that has not bought them does not get
 * a locked page — it gets no page, because the endpoints behind it are closed.
 *
 * <p>This is the company-level entitlement, which is a different question from the per-user permission
 * matrix in {@link PermissionBoundaryIntegrationTest}: there the subject is "may this user", here it is
 * "does this company pay for it at all". An owner with every permission still gets 403 without the add-on,
 * which is exactly what these tests pin down.</p>
 */
class AddonGatingIntegrationTest extends ApiTestBase {

    private static final List<String> TENDER_PATHS = List.of(
            "/api/tenders?size=1",
            "/api/tenders/1");

    private static final List<String> EMAIL_PATHS = List.of(
            "/api/sent-emails?size=1",
            "/api/sent-email-batches?size=1",
            "/api/email-templates");

    @Test
    @DisplayName("without the add-ons even an owner cannot reach the tender or email endpoints")
    void unboughtFeaturesAreClosed() throws Exception {
        Tenant owner = newBusinessWithoutAddons();

        for (String path : TENDER_PATHS) {
            assertThat(statusOfGet(path, owner)).as(path).isEqualTo(403);
        }
        for (String path : EMAIL_PATHS) {
            assertThat(statusOfGet(path, owner)).as(path).isEqualTo(403);
        }
        // Writes are closed by the same rule, so a client cannot skip the list page and post directly.
        assertThat(statusOf(delete("/api/tenders/999999"), owner)).as("delete a tender").isEqualTo(403);
    }

    @Test
    @DisplayName("everything else on the same plan is unaffected")
    void therestOfTheAppStillWorks() throws Exception {
        Tenant owner = newBusinessWithoutAddons();

        for (String path : List.of("/api/products?size=1", "/api/sales-orders?size=1", "/api/warehouses")) {
            assertThat(statusOfGet(path, owner)).as(path).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("the dashboard leaves the tender block out rather than reporting zeroes")
    void dashboardOmitsTendersWithoutTheAddon() throws Exception {
        JsonNode without = readJson(mvc.perform(authed(get("/api/dashboard/stats"), newBusinessWithoutAddons()))
                .andReturn());
        assertThat(without.hasNonNull("tenders")).as("no add-on").isFalse();

        JsonNode with = readJson(mvc.perform(authed(get("/api/dashboard/stats"), newBusiness())).andReturn());
        assertThat(with.hasNonNull("tenders")).as("add-on bought").isTrue();
    }

    @Test
    @DisplayName("buying the add-on opens the endpoints, and it shows on the session profile")
    void buyingTheAddonOpensTheFeature() throws Exception {
        Tenant owner = newBusinessWithoutAddons();
        assertThat(statusOfGet("/api/tenders?size=1", owner)).as("before").isEqualTo(403);

        assertThat(statusOf(post("/api/subscription/addons/TENDERS"), owner)).isEqualTo(200);

        assertThat(statusOfGet("/api/tenders?size=1", owner)).as("after").isEqualTo(200);
        // Still only the one that was bought.
        assertThat(statusOfGet("/api/sent-email-batches?size=1", owner)).as("the other one").isEqualTo(403);

        JsonNode me = readJson(mvc.perform(authed(get("/api/auth/me"), owner)).andReturn());
        assertThat(me.path("addons")).singleElement().extracting(JsonNode::asText).isEqualTo("TENDERS");
    }

    @Test
    @DisplayName("a signup can buy the add-ons, and an unknown one is refused outright")
    void signupCarriesTheAddons() throws Exception {
        JsonNode me = readJson(mvc.perform(authed(get("/api/auth/me"), newBusiness())).andReturn());
        assertThat(me.path("addons")).hasSize(2);

        // The signup form quotes a monthly total, so an add-on it cannot honour has to fail the whole
        // request - and fail it before any company row is written (registration is deliberately not one
        // transaction, so a late throw would strand a real company).
        int status = mvc.perform(post("/api/public/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"companyName":"IT Bad Addon","fullName":"IT Owner","email":"it-bad-addon@test.local",
                          "password":"testpass123","accountType":"BUSINESS","plan":"STARTER","addons":["NOPE"]}
                         """)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);

        // ...and the refused email is still free, which proves no owner row survived the rejection.
        int retry = mvc.perform(post("/api/public/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"companyName":"IT Bad Addon","fullName":"IT Owner","email":"it-bad-addon@test.local",
                          "password":"testpass123","accountType":"BUSINESS","plan":"STARTER"}
                         """)).andReturn().getResponse().getStatus();
        assertThat(retry).as("the same address is still available").isEqualTo(200);
    }
}
