package com.example.skladdo.controller;

import com.example.skladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Multi-tenancy is the property this product cannot afford to get wrong: every company's data lives in
 * shared tables, separated only by Hibernate's {@code @TenantId} discriminator. These tests act as a
 * fully-privileged OWNER of one company and try to reach another company's records by id.
 *
 * <p>The expected answer is always <b>404, not 403</b>. A 403 would confirm the record exists, turning any
 * id into an oracle for how much data a competitor holds; 404 makes "belongs to someone else" and "does
 * not exist" indistinguishable.</p>
 */
class TenantIsolationIntegrationTest extends ApiTestBase {

    @Test
    @DisplayName("a brand-new company starts empty and sees nothing from anyone else")
    void newCompanyStartsEmpty() throws Exception {
        Tenant established = newBusiness();
        createClient(established, "Established Client");
        createWarehouse(established, "Established Depot");

        Tenant fresh = newBusiness();

        assertThat(contentSize(fresh, "/api/clients?size=50")).isZero();
        assertThat(contentSize(fresh, "/api/products?size=50")).isZero();
        assertThat(contentSize(fresh, "/api/services?size=50")).isZero();
        assertThat(contentSize(fresh, "/api/service-categories?size=50")).isZero();
        assertThat(contentSize(fresh, "/api/sales-orders?size=50")).isZero();
        assertThat(contentSize(fresh, "/api/purchase-orders?size=50")).isZero();
        assertThat(contentSize(fresh, "/api/tenders?size=50")).isZero();
        assertThat(contentSize(fresh, "/api/audit-logs?size=50")).isZero();
        // Plain arrays rather than pages.
        assertThat(readJson(mvc.perform(authed(get("/api/warehouses"), fresh)).andReturn())).isEmpty();
        // Its own owner is the one user it can see.
        assertThat(readJson(mvc.perform(authed(get("/api/users"), fresh)).andReturn())).hasSize(1);
    }

    @Test
    @DisplayName("reading another company's record by id returns 404, not 403")
    void crossTenantReadIsNotFound() throws Exception {
        Tenant victim = newBusiness();
        Tenant attacker = newBusiness();
        long clientId = createClient(victim, "Victim Client");
        long warehouseId = createWarehouse(victim, "Victim Depot");

        assertThat(statusOfGet("/api/clients/" + clientId, attacker)).isEqualTo(404);
        assertThat(statusOfGet("/api/warehouses/" + warehouseId, attacker)).isEqualTo(404);

        // The victim still reads its own records, so the 404s above are isolation and not breakage.
        assertThat(statusOfGet("/api/clients/" + clientId, victim)).isEqualTo(200);
        assertThat(statusOfGet("/api/warehouses/" + warehouseId, victim)).isEqualTo(200);
    }

    @Test
    @DisplayName("writing to another company's record fails and leaves it untouched")
    void crossTenantWriteIsRefusedAndChangesNothing() throws Exception {
        Tenant victim = newBusiness();
        Tenant attacker = newBusiness();
        long clientId = createClient(victim, "Original Name");

        int updateStatus = statusOf(put("/api/clients/" + clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"Renamed By Attacker"}
                         """), attacker);

        assertThat(updateStatus).isEqualTo(404);
        JsonNode afterwards = readJson(mvc.perform(authed(get("/api/clients/" + clientId), victim)).andReturn());
        assertThat(afterwards.path("name").asText()).isEqualTo("Original Name");
    }

    @Test
    @DisplayName("deleting another company's records fails and destroys nothing")
    void crossTenantDeleteDestroysNothing() throws Exception {
        Tenant victim = newBusiness();
        Tenant attacker = newBusiness();
        long clientId = createClient(victim, "Keep Me");
        long warehouseId = createWarehouse(victim, "Keep This Depot");

        assertThat(statusOf(delete("/api/clients/" + clientId), attacker)).isEqualTo(404);
        assertThat(statusOf(delete("/api/warehouses/" + warehouseId), attacker)).isEqualTo(404);

        assertThat(statusOfGet("/api/clients/" + clientId, victim)).as("client survived").isEqualTo(200);
        assertThat(statusOfGet("/api/warehouses/" + warehouseId, victim)).as("warehouse survived").isEqualTo(200);
    }

    /**
     * Regression guard for F-006. The delete used to run unconditionally, which returned 200 and wrote an
     * audit row naming another company's id — a deletion that never happened, recorded as though it had.
     */
    @Test
    @DisplayName("a failed cross-tenant delete writes no audit row")
    void failedCrossTenantDeleteLeavesNoAuditTrace() throws Exception {
        Tenant victim = newBusiness();
        Tenant attacker = newBusiness();
        long clientId = createClient(victim, "Audit Probe");
        int before = totalAuditRows(attacker);

        assertThat(statusOf(delete("/api/clients/" + clientId), attacker)).isEqualTo(404);
        assertThat(statusOf(delete("/api/tenders/999999"), attacker)).isEqualTo(404);

        assertThat(totalAuditRows(attacker)).as("no phantom audit rows").isEqualTo(before);
    }

    @Test
    @DisplayName("users cannot be read or administered across companies")
    void crossTenantUserAccessIsRefused() throws Exception {
        Tenant victim = newBusiness();
        Tenant attacker = newBusiness();
        Tenant victimUser = inviteUser(victim, "USER");

        assertThat(statusOfGet("/api/users/" + victimUser.userId(), attacker)).isEqualTo(404);
        assertThat(statusOf(post("/api/users/" + victimUser.userId() + "/setup-email"), attacker)).isEqualTo(404);
        assertThat(statusOf(delete("/api/users/" + victimUser.userId()), attacker)).isEqualTo(404);
        assertThat(statusOf(put("/api/users/" + victimUser.userId() + "/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"permissions":[{"module":"PRODUCTS","canView":true,"canCreate":true,"canEdit":true,"canDelete":true}]}
                         """), attacker)).isEqualTo(404);

        // The victim's own admin is unaffected.
        assertThat(statusOfGet("/api/users/" + victimUser.userId(), victim)).isEqualTo(200);
    }

    @Test
    @DisplayName("a company cannot rename another company or read its settings")
    void companyRecordIsScopedToTheCaller() throws Exception {
        Tenant a = newBusiness();
        Tenant b = newBusiness();

        JsonNode aCompany = readJson(mvc.perform(authed(get("/api/company"), a)).andReturn());
        JsonNode bCompany = readJson(mvc.perform(authed(get("/api/company"), b)).andReturn());

        // /api/company is pinned to the caller's own company - there is no id to tamper with, which is
        // the point: neither can name the other.
        assertThat(aCompany.path("id").asLong()).isEqualTo(a.companyId());
        assertThat(bCompany.path("id").asLong()).isEqualTo(b.companyId());
        assertThat(aCompany.path("id").asLong()).isNotEqualTo(bCompany.path("id").asLong());
    }

    @Test
    @DisplayName("switching into a company you have no connection to is refused")
    void cannotSwitchIntoAnUnconnectedCompany() throws Exception {
        Tenant a = newBusiness();
        Tenant b = newBusiness();

        int status = statusOf(post("/api/auth/switch-company")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"companyId":%d}
                         """.formatted(b.companyId())), a);

        assertThat(status).isEqualTo(403);
    }

    @Test
    @DisplayName("audit trails do not bleed between companies")
    void auditTrailsAreScoped() throws Exception {
        Tenant a = newBusiness();
        Tenant b = newBusiness();
        createClient(a, "A's Client");
        createClient(a, "Another A Client");

        assertThat(totalAuditRows(a)).as("company A recorded its own creates").isGreaterThan(0);
        assertThat(totalAuditRows(b)).as("company B saw none of them").isZero();
    }

    // -------------------------------------------------------------------------------------------------
    // fixtures
    // -------------------------------------------------------------------------------------------------

    private long createClient(Tenant tenant, String name) throws Exception {
        return readJson(mvc.perform(authed(post("/api/clients"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s"}
                         """.formatted(name))).andReturn()).path("id").asLong();
    }

    private long createWarehouse(Tenant tenant, String name) throws Exception {
        return readJson(mvc.perform(authed(post("/api/warehouses"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s"}
                         """.formatted(name))).andReturn()).path("id").asLong();
    }

    private int contentSize(Tenant tenant, String path) throws Exception {
        return readJson(mvc.perform(authed(get(path), tenant)).andReturn()).path("content").size();
    }

    private int totalAuditRows(Tenant tenant) throws Exception {
        return readJson(mvc.perform(authed(get("/api/audit-logs?size=1"), tenant)).andReturn())
                .path("totalElements").asInt();
    }
}
