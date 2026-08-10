package com.example.kladdo.controller;

import com.example.kladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The connection-code lifecycle and the client/warehouse ownership boundary that governs it - the
 * mechanics behind a warehouse account working inside a client company. {@link PermissionBoundaryIntegrationTest}
 * already covers the permission ceiling a partner session runs on and that revoking cuts it off immediately;
 * these tests cover getting to (and out of) that state correctly in the first place.
 */
class WarehousePartnerIntegrationTest extends ApiTestBase {

    /**
     * Regression guard for F-014. {@code issueCode()} is supposed to abandon every outstanding code when a
     * fresh one is drawn - {@code ConnectionCode.expiresAt} was mistakenly {@code updatable = false}, so
     * that write silently never reached the database and the "abandoned" code kept working for its full
     * 48-hour life.
     */
    @Test
    @DisplayName("regenerating the code invalidates the old one, and the new one still works")
    void regeneratingTheCodeInvalidatesTheOldOne() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();

        String oldCode = currentCode(client);
        String newCode = regenerateCode(client);
        assertThat(newCode).isNotEqualTo(oldCode);

        assertThat(statusOf(redeemRequest(oldCode), operator)).as("abandoned code").isEqualTo(400);
        assertThat(statusOf(redeemRequest(newCode), operator)).as("fresh code").isEqualTo(200);
    }

    @Test
    @DisplayName("a code cannot be redeemed twice")
    void codeIsSingleUse() throws Exception {
        Tenant client = newBusiness();
        Tenant firstOperator = newWarehouseAccount();
        Tenant secondOperator = newWarehouseAccount();

        String code = currentCode(client);
        assertThat(statusOf(redeemRequest(code), firstOperator)).isEqualTo(200);
        assertThat(statusOf(redeemRequest(code), secondOperator)).as("already redeemed").isEqualTo(400);
    }

    @Test
    @DisplayName("only a BUSINESS company issues codes, and only a WAREHOUSE account redeems them")
    void accountTypeIsEnforcedBothWays() throws Exception {
        Tenant business = newBusiness();
        Tenant warehouse = newWarehouseAccount();

        assertThat(statusOfGet("/api/warehouse-partners/code", warehouse)).as("warehouse issuing").isEqualTo(403);

        String code = currentCode(business);
        assertThat(statusOf(redeemRequest(code), business)).as("business redeeming").isEqualTo(403);
        // The code survives the refused attempt above and still works for the account type it's meant for.
        assertThat(statusOf(redeemRequest(code), warehouse)).isEqualTo(200);
    }

    @Test
    @DisplayName("an unknown code is rejected")
    void unknownCodeIsRejected() throws Exception {
        Tenant warehouse = newWarehouseAccount();
        assertThat(statusOf(redeemRequest("CO-ZZZZ-ZZZZ"), warehouse)).isEqualTo(400);
    }

    @Test
    @DisplayName("assigning warehouses and toggling price visibility are the client's alone")
    void settingWarehousesAndPricesIsClientOnly() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        connect(client, operator);
        long connectionId = onlyConnectionId(client);
        long warehouseId = createWarehouse(client, "Client Depot");

        // The operator, at home (not switched into the client) - still refused, this time on the
        // service's own client-only check rather than the role gate a partner session would hit first.
        assertThat(statusOf(put("/api/warehouse-partners/" + connectionId + "/warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseIds\":[" + warehouseId + "]}"), operator)).isEqualTo(403);
        assertThat(statusOf(put("/api/warehouse-partners/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"canSeePrices\":true}"), operator)).isEqualTo(403);

        // The client can do both.
        assertThat(statusOf(put("/api/warehouse-partners/" + connectionId + "/warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseIds\":[" + warehouseId + "]}"), client)).isEqualTo(200);
        assertThat(statusOf(put("/api/warehouse-partners/" + connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"canSeePrices\":true}"), client)).isEqualTo(200);
    }

    @Test
    @DisplayName("an empty warehouse assignment grants nothing, and a later assignment needs no re-switch")
    void emptyAssignmentGrantsNothingUntilAssignedLive() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        Tenant partner = connect(client, operator);
        long connectionId = onlyConnectionId(client);

        assertThat(readJson(mvc.perform(authed(get("/api/warehouses"), partner)).andReturn()))
                .as("nothing assigned yet").isEmpty();

        long warehouseId = createWarehouse(client, "Late-assigned Depot");
        mvc.perform(authed(put("/api/warehouse-partners/" + connectionId + "/warehouses"), client)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseIds\":[" + warehouseId + "]}"));

        // Same token as above, never re-switched: access is checked live against the connection, not cached.
        JsonNode warehouses = readJson(mvc.perform(authed(get("/api/warehouses"), partner)).andReturn());
        assertThat(warehouses).hasSize(1);
        assertThat(warehouses.get(0).path("id").asLong()).isEqualTo(warehouseId);
    }

    @Test
    @DisplayName("a connection is invisible and untouchable to anyone not party to it")
    void connectionIsScopedToItsTwoParties() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        connect(client, operator);
        long connectionId = onlyConnectionId(client);
        Tenant outsider = newBusiness();

        // Reported as missing, not forbidden - an outsider should not learn the id even exists.
        assertThat(statusOf(post("/api/warehouse-partners/" + connectionId + "/disconnect"), outsider))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a terminated connection cannot be disconnected again")
    void disconnectingATerminatedConnectionIsRejected() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        connect(client, operator);
        long connectionId = onlyConnectionId(client);

        assertThat(statusOf(post("/api/warehouse-partners/" + connectionId + "/disconnect"), client)).isEqualTo(200);
        assertThat(statusOf(post("/api/warehouse-partners/" + connectionId + "/disconnect"), client))
                .as("already revoked").isEqualTo(400);
    }

    @Test
    @DisplayName("connecting and disconnecting are both recorded on both companies' audit trails")
    void connectAndDisconnectAreAuditedOnBothSides() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        connect(client, operator);
        long connectionId = onlyConnectionId(client);
        Tenant operatorHome = switchInto(operator, operator.companyId());

        assertThat(totalAuditRows(client)).as("client saw the CONNECT").isGreaterThan(0);
        assertThat(totalAuditRows(operatorHome)).as("operator saw the CONNECT too").isGreaterThan(0);

        int clientBefore = totalAuditRows(client);
        int operatorBefore = totalAuditRows(operatorHome);
        mvc.perform(authed(post("/api/warehouse-partners/" + connectionId + "/disconnect"), client));

        assertThat(totalAuditRows(client)).isGreaterThan(clientBefore);
        assertThat(totalAuditRows(operatorHome)).isGreaterThan(operatorBefore);
    }

    /**
     * Finding N-006. {@code canSeePrices} used to be honoured only by the frontend hiding columns, so a
     * partner's staff could read the client's prices straight off the API. It is now enforced server-side.
     */
    @Test
    @DisplayName("a partner who may not see prices gets them stripped from the response")
    void pricesAreRedactedForAPriceBlindPartner() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        Tenant partner = connect(client, operator);
        long connectionId = onlyConnectionId(client);
        long productId = createPricedProduct(client);

        // Connections start with canSeePrices = false.
        JsonNode hidden = readJson(mvc.perform(authed(get("/api/products/" + productId), partner)).andReturn());
        assertThat(hidden.path("name").asText()).as("still does its job").isEqualTo("Priced Product");
        assertThat(hidden.path("stockQuantity").isNull()).as("quantities are not money").isFalse();
        assertThat(hidden.path("price").isNull()).as("price is stripped").isTrue();

        // The client turns price visibility on; the same partner token now sees them.
        mvc.perform(authed(put("/api/warehouse-partners/" + connectionId), client)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"canSeePrices":true}
                         """));
        JsonNode shown = readJson(mvc.perform(authed(get("/api/products/" + productId), partner)).andReturn());
        assertThat(shown.path("price").decimalValue()).isEqualByComparingTo("555.55");

        // And the client's own owner was never affected either way.
        JsonNode owner = readJson(mvc.perform(authed(get("/api/products/" + productId), client)).andReturn());
        assertThat(owner.path("price").decimalValue()).isEqualByComparingTo("555.55");
    }

    @Test
    @DisplayName("redaction reaches nested order lines and totals, not just the top level")
    void redactionReachesNestedMoney() throws Exception {
        Tenant client = newBusiness();
        Tenant operator = newWarehouseAccount();
        Tenant partner = connect(client, operator);
        long productId = createPricedProduct(client);
        long clientId = created(client, "/api/clients", "{\"name\":\"Buyer\"}");
        long warehouseId = createWarehouse(client, "Order Depot");
        long orderId = created(client, "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"productId":%d,"quantity":2,"unitPrice":10}]}
                """.formatted(clientId, warehouseId, productId));

        JsonNode order = readJson(mvc.perform(authed(get("/api/sales-orders/" + orderId), partner)).andReturn());

        assertThat(order.path("totalAmount").isNull()).as("order total").isTrue();
        assertThat(order.path("subtotalAmount").isNull()).as("order subtotal").isTrue();
        assertThat(order.path("items").get(0).path("unitPrice").isNull()).as("nested line price").isTrue();
        assertThat(order.path("items").get(0).path("lineTotal").isNull()).as("nested line total").isTrue();
        // The things a warehouse actually needs survive.
        assertThat(order.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(order.path("orderNumber").asText()).isNotBlank();
    }

    // -------------------------------------------------------------------------------------------------
    // fixtures
    // -------------------------------------------------------------------------------------------------

    private long created(Tenant tenant, String path, String body) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("id").asLong();
    }

    private long createPricedProduct(Tenant tenant) throws Exception {
        long categoryId = created(tenant, "/api/categories", "{\"name\":\"Priced\"}");
        long manufacturerId = created(tenant, "/api/manufacturers", "{\"name\":\"Priced Maker\"}");
        return created(tenant, "/api/products", """
                {"name":"Priced Product","price":555.55,"stockQuantity":7,
                 "manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));
    }

    private String currentCode(Tenant client) throws Exception {
        return readJson(mvc.perform(authed(get("/api/warehouse-partners/code"), client)).andReturn())
                .path("code").asText();
    }

    private String regenerateCode(Tenant client) throws Exception {
        return readJson(mvc.perform(authed(post("/api/warehouse-partners/code/regenerate"), client)).andReturn())
                .path("code").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder redeemRequest(String code) {
        return post("/api/warehouse-partners/redeem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"code":"%s"}
                         """.formatted(code));
    }

    private long onlyConnectionId(Tenant client) throws Exception {
        JsonNode connections = readJson(mvc.perform(authed(get("/api/warehouse-partners"), client)).andReturn());
        return connections.get(0).path("id").asLong();
    }

    private long createWarehouse(Tenant tenant, String name) throws Exception {
        return readJson(mvc.perform(authed(post("/api/warehouses"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s"}
                         """.formatted(name))).andReturn()).path("id").asLong();
    }

    private int totalAuditRows(Tenant tenant) throws Exception {
        return readJson(mvc.perform(authed(get("/api/audit-logs?size=1"), tenant)).andReturn())
                .path("totalElements").asInt();
    }
}
