package com.example.kladdo.controller;

import com.example.kladdo.support.ApiTestBase;
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
 * The authorization matrix, enforced server-side. The frontend hides controls a user may not use, but that
 * is cosmetics — these tests assert the API refuses regardless of what the client chose to render.
 *
 * <p>Write access is probed with {@code DELETE /{module}/999999}. That is deliberate: {@code POST} bodies
 * are {@code @Valid}-checked during argument resolution, which happens <em>before</em> the
 * {@code @PreAuthorize} interceptor, so an unauthorised POST returns 400 and tells you nothing about
 * authorization. DELETE carries no body, so the permission check is the first gate and the status is
 * unambiguous: <b>403 = denied, 404 = allowed (record simply absent)</b>.</p>
 */
class PermissionBoundaryIntegrationTest extends ApiTestBase {

    /** List endpoints, one per module, used for read probes. */
    private static final List<String> READ_PATHS = List.of(
            "/api/products?size=1",
            "/api/manufacturers?size=1",
            "/api/clients?size=1",
            "/api/purchase-orders?size=1",
            "/api/sales-orders?size=1",
            "/api/invoices?size=1",
            "/api/tenders?size=1",
            "/api/warehouses",
            "/api/sent-emails?size=1");

    /** Collection roots whose DELETE is guarded by that module's canDelete. */
    private static final List<String> DELETE_PATHS = List.of(
            "/api/products/999999",
            "/api/manufacturers/999999",
            "/api/clients/999999",
            "/api/purchase-orders/999999",
            "/api/sales-orders/999999",
            "/api/tenders/999999",
            "/api/warehouses/999999",
            "/api/categories/999999");

    /** Endpoints gated by role rather than by a permission module. */
    private static final List<String> ADMIN_PATHS = List.of(
            "/api/users",
            "/api/settings",
            "/api/company",
            "/api/audit-logs?size=1",
            "/api/subscription");

    // -------------------------------------------------------------------------------------------------
    // managers
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an OWNER reaches every module and every admin surface")
    void ownerHasFullAccess() throws Exception {
        Tenant owner = newBusiness();

        for (String path : READ_PATHS) {
            assertThat(statusOfGet(path, owner)).as(path).isEqualTo(200);
        }
        for (String path : ADMIN_PATHS) {
            assertThat(statusOfGet(path, owner)).as(path).isEqualTo(200);
        }
        for (String path : DELETE_PATHS) {
            // Authorised, so the only reason to fail is that the record does not exist.
            assertThat(statusOf(delete(path), owner)).as(path).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("an ADMINISTRATOR has the same reach as the owner")
    void administratorHasFullAccess() throws Exception {
        Tenant owner = newBusiness();
        Tenant admin = inviteUser(owner, "ADMINISTRATOR");

        for (String path : READ_PATHS) {
            assertThat(statusOfGet(path, admin)).as(path).isEqualTo(200);
        }
        for (String path : ADMIN_PATHS) {
            assertThat(statusOfGet(path, admin)).as(path).isEqualTo(200);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // restricted users
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a user with no permissions at all is refused everything, including admin surfaces")
    void userWithoutGrantsIsRefused() throws Exception {
        Tenant owner = newBusiness();
        Tenant user = inviteUser(owner, "USER");
        setPermissions(owner, user.userId()); // no grants

        for (String path : READ_PATHS) {
            assertThat(statusOfGet(path, user)).as(path).isEqualTo(403);
        }
        for (String path : ADMIN_PATHS) {
            assertThat(statusOfGet(path, user)).as("admin " + path).isEqualTo(403);
        }
        for (String path : DELETE_PATHS) {
            assertThat(statusOf(delete(path), user)).as(path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("view-only on one module opens that module and nothing else")
    void viewOnlyGrantIsHonoured() throws Exception {
        Tenant owner = newBusiness();
        Tenant user = inviteUser(owner, "USER");
        setPermissions(owner, user.userId(), grant("TENDERS", true, false, false, false));

        assertThat(statusOfGet("/api/tenders?size=1", user)).as("granted module").isEqualTo(200);
        assertThat(statusOfGet("/api/invoices?size=1", user)).as("ungranted module").isEqualTo(403);
        assertThat(statusOf(delete("/api/tenders/999999"), user)).as("view does not imply delete").isEqualTo(403);
    }

    @Test
    @DisplayName("delete is granted independently of view")
    void deleteFlagIsIndependent() throws Exception {
        Tenant owner = newBusiness();
        Tenant canDelete = inviteUser(owner, "USER");
        Tenant cannotDelete = inviteUser(owner, "USER");
        setPermissions(owner, canDelete.userId(), grant("PRODUCTS", true, true, true, true));
        setPermissions(owner, cannotDelete.userId(), grant("PRODUCTS", true, true, true, false));

        assertThat(statusOf(delete("/api/products/999999"), canDelete)).isEqualTo(404);
        assertThat(statusOf(delete("/api/products/999999"), cannotDelete)).isEqualTo(403);
    }

    /**
     * Reference data is readable when another grant depends on it — otherwise a user allowed to write sales
     * orders could not load the client or warehouse pickers those orders need. Pins the derivation so the
     * next change to {@code canReadReference} has to be deliberate.
     */
    @Test
    @DisplayName("an order grant opens the reference data its forms depend on, but not unrelated modules")
    void referenceDependenciesAreDerived() throws Exception {
        Tenant owner = newBusiness();
        Tenant salesUser = inviteUser(owner, "USER");
        setPermissions(owner, salesUser.userId(), grant("SALES_ORDERS", true, true, true, false));

        assertThat(statusOfGet("/api/clients?size=1", salesUser)).as("clients: needed to pick a customer").isEqualTo(200);
        assertThat(statusOfGet("/api/products?size=1", salesUser)).as("products: needed for order lines").isEqualTo(200);
        assertThat(statusOfGet("/api/warehouses", salesUser)).as("warehouses: needed to pick a warehouse").isEqualTo(200);

        assertThat(statusOfGet("/api/tenders?size=1", salesUser)).as("tenders: unrelated").isEqualTo(403);
        assertThat(statusOfGet("/api/sent-emails?size=1", salesUser)).as("emails: unrelated").isEqualTo(403);

        // Derived read access must not become write access.
        assertThat(statusOf(delete("/api/clients/999999"), salesUser)).as("no delete on derived module").isEqualTo(403);
    }

    @Test
    @DisplayName("the declared permission map matches what the API enforces for granted modules")
    void declaredMapMatchesEnforcementForDirectGrants() throws Exception {
        Tenant owner = newBusiness();
        Tenant user = inviteUser(owner, "USER");
        setPermissions(owner, user.userId(),
                grant("TENDERS", true, false, false, false),
                grant("INVOICES", true, false, false, false));

        JsonNode permissions = readJson(mvc.perform(authed(get("/api/auth/me"), user)).andReturn())
                .path("permissions");

        assertThat(permissions.path("TENDERS").path("canView").asBoolean()).isTrue();
        assertThat(permissions.path("INVOICES").path("canView").asBoolean()).isTrue();
        assertThat(permissions.path("SALES_ORDERS").path("canView").asBoolean()).isFalse();
        assertThat(statusOfGet("/api/tenders?size=1", user)).isEqualTo(200);
        assertThat(statusOfGet("/api/sales-orders?size=1", user)).isEqualTo(403);
    }

    // -------------------------------------------------------------------------------------------------
    // warehouse account at home
    // -------------------------------------------------------------------------------------------------

    /**
     * The account-type separation is checked <em>before</em> the OWNER/ADMINISTRATOR bypass, so owning a
     * warehouse account does not open a catalogue that is not supposed to exist. Losing that ordering would
     * hand every 3PL owner a full business tenant, which is why it is asserted explicitly.
     */
    @Test
    @DisplayName("a warehouse account's OWNER is refused every business module in its own tenant")
    void warehouseAccountOwnsNoBusinessDataAtHome() throws Exception {
        Tenant warehouse = newWarehouseAccount();

        for (String path : READ_PATHS) {
            assertThat(statusOfGet(path, warehouse)).as(path).isEqualTo(403);
        }

        JsonNode permissions = readJson(mvc.perform(authed(get("/api/auth/me"), warehouse)).andReturn())
                .path("permissions");
        permissions.properties().forEach(entry ->
                assertThat(entry.getValue().path("canView").asBoolean())
                        .as("declared canView for " + entry.getKey())
                        .isFalse());
    }

    @Test
    @DisplayName("a warehouse account still administers itself")
    void warehouseAccountAdministersItself() throws Exception {
        Tenant warehouse = newWarehouseAccount();

        for (String path : ADMIN_PATHS) {
            assertThat(statusOfGet(path, warehouse)).as(path).isEqualTo(200);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // partner sessions
    // -------------------------------------------------------------------------------------------------

    /**
     * Inside a client company the operator runs on a fixed grant, not on their own permission rows — they
     * are an OWNER at home, and that must not follow them in.
     */
    @Test
    @DisplayName("a partner session is capped by the fixed grant, whatever the operator is at home")
    void partnerSessionIsCappedByTheFixedGrant() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        Tenant partner = connect(client, operator);

        JsonNode me = readJson(mvc.perform(authed(get("/api/auth/me"), partner)).andReturn());
        assertThat(me.path("partnerSession").asBoolean()).isTrue();
        assertThat(me.path("companyId").asLong()).isEqualTo(client.companyId());
        assertThat(me.path("role").asText()).as("role capped inside the client").isEqualTo("WAREHOUSE");
        assertThat(me.path("homeRole").asText()).isEqualTo("OWNER");

        // Granted by warehouseDefaults(): orders, products, warehouses, and the catalogue they refer to.
        assertThat(statusOfGet("/api/sales-orders?size=1", partner)).isEqualTo(200);
        assertThat(statusOfGet("/api/purchase-orders?size=1", partner)).isEqualTo(200);
        assertThat(statusOfGet("/api/products?size=1", partner)).isEqualTo(200);
        assertThat(statusOfGet("/api/warehouses", partner)).isEqualTo(200);
        assertThat(statusOfGet("/api/clients?size=1", partner)).isEqualTo(200);

        // Outside the grant entirely.
        assertThat(statusOfGet("/api/tenders?size=1", partner)).as("tenders").isEqualTo(403);
        assertThat(statusOfGet("/api/invoices?size=1", partner)).as("invoices").isEqualTo(403);
        assertThat(statusOfGet("/api/sent-emails?size=1", partner)).as("emails").isEqualTo(403);

        // No delete on anything, and no admin reach into someone else's company.
        for (String path : DELETE_PATHS) {
            assertThat(statusOf(delete(path), partner)).as(path).isEqualTo(403);
        }
        for (String path : ADMIN_PATHS) {
            assertThat(statusOfGet(path, partner)).as("admin " + path).isEqualTo(403);
        }
    }

    /**
     * Revoking must bite immediately rather than when the token expires — the active company is re-checked
     * against a live connection on every request, and a stale claim falls back to the operator's own tenant.
     */
    @Test
    @DisplayName("revoking a connection cuts an existing partner token off at once")
    void revokingAConnectionCutsAccessImmediately() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        Tenant partner = connect(client, operator);
        assertThat(statusOfGet("/api/products?size=1", partner)).as("before revoke").isEqualTo(200);

        JsonNode connections = readJson(mvc.perform(authed(get("/api/warehouse-partners"), client)).andReturn());
        long connectionId = connections.get(0).path("id").asLong();
        mvc.perform(authed(post("/api/warehouse-partners/" + connectionId + "/disconnect"), client));

        // Same token, not yet expired.
        assertThat(statusOfGet("/api/products?size=1", partner)).as("after revoke").isEqualTo(403);

        JsonNode me = readJson(mvc.perform(authed(get("/api/auth/me"), partner)).andReturn());
        assertThat(me.path("companyId").asLong()).as("falls back to its own company").isEqualTo(operator.companyId());
        assertThat(me.path("partnerSession").asBoolean()).isFalse();
    }
}
