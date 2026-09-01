package com.example.skladdo.controller;

import com.example.skladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Services on sales orders.
 *
 * <p>The invariant these tests exist to protect: <b>a line selling a service never moves stock, and a
 * mixed order still moves stock for its product lines exactly as before.</b> A service has no warehouse
 * presence at all, so if one ever reached {@code adjustWarehouseStock} it would either corrupt an
 * unrelated product's count or fail the whole status change — and the derived stock ledger, which
 * replays status transitions, would disagree with the warehouse from then on.</p>
 *
 * <p>The order-item FK that used to guarantee this ({@code product_id NOT NULL}) is precisely what had
 * to be relaxed to allow services, so the guarantee now lives in code and needs a test to hold it.</p>
 */
class ServiceLineIntegrationTest extends ApiTestBase {

    private record Fixture(Tenant owner, long productId, long serviceId, long warehouseId, long clientId) {
    }

    private Fixture fixture(int startingStock) throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/categories", """
                                                            {"name":"Service Test Category"}
                                                            """);
        long manufacturerId = created(owner, "/api/manufacturers", """
                                                                   {"name":"Service Test Manufacturer"}
                                                                   """);
        long clientId = created(owner, "/api/clients", """
                                                       {"name":"Service Test Client"}
                                                       """);
        long warehouseId = created(owner, "/api/warehouses", """
                                                             {"name":"Service Test Depot"}
                                                             """);
        long productId = created(owner, "/api/products", """
                {"name":"Service Test Product","price":100,"manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));
        long serviceId = created(owner, "/api/services", """
                                                         {"name":"Installation","code":"INST-1","price":45}
                                                         """);

        if (startingStock > 0) {
            mvc.perform(authed(post("/api/products/" + productId + "/adjustments"), owner)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                             {"warehouseId":%d,"quantityChange":%d,"note":"seed"}
                             """.formatted(warehouseId, startingStock)));
        }
        return new Fixture(owner, productId, serviceId, warehouseId, clientId);
    }

    // -------------------------------------------------------------------------------------------------
    // the stock boundary, for services
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a service-only order never moves stock, at any status")
    void serviceOnlyOrderNeverMovesStock() throws Exception {
        Fixture f = fixture(100);
        long orderId = created(f.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"serviceId":%d,"quantity":3,"unitPrice":45}]}
                """.formatted(f.clientId(), f.warehouseId(), f.serviceId()));

        // The full walk, including both stock-affecting statuses and the way back out of them.
        for (String status : new String[]{"IN_PROGRESS", "CONFIRMED", "SHIPPED", "CLOSED", "CONFIRMED", "NEW"}) {
            assertThat(setStatus(f.owner(), orderId, status)).as("status %s accepted", status).isEqualTo(200);
            assertThat(stock(f)).as("stock after %s", status).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("a mixed order moves stock for its product line only")
    void mixedOrderMovesStockForTheProductLineOnly() throws Exception {
        Fixture f = fixture(100);
        long orderId = created(f.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"productId":%d,"quantity":10,"unitPrice":100},
                          {"serviceId":%d,"quantity":4,"unitPrice":45}]}
                """.formatted(f.clientId(), f.warehouseId(), f.productId(), f.serviceId()));

        assertThat(stock(f)).as("NEW").isEqualTo(100);

        setStatus(f.owner(), orderId, "SHIPPED");
        // 10, not 14: the service's four units are not units of anything in a warehouse.
        assertThat(stock(f)).as("SHIPPED issues only the product's units").isEqualTo(90);

        setStatus(f.owner(), orderId, "NEW");
        assertThat(stock(f)).as("stepping back restores exactly what was issued").isEqualTo(100);
    }

    @Test
    @DisplayName("deleting a shipped mixed order returns only the product's stock")
    void deletingAShippedMixedOrderReturnsOnlyProductStock() throws Exception {
        Fixture f = fixture(100);
        long orderId = created(f.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"SHIPPED",
                 "items":[{"productId":%d,"quantity":10,"unitPrice":100},
                          {"serviceId":%d,"quantity":4,"unitPrice":45}]}
                """.formatted(f.clientId(), f.warehouseId(), f.productId(), f.serviceId()));
        assertThat(stock(f)).as("created straight into SHIPPED").isEqualTo(90);

        mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/sales-orders/" + orderId), f.owner()));
        assertThat(stock(f)).as("delete restores the product's units").isEqualTo(100);
    }

    // -------------------------------------------------------------------------------------------------
    // "exactly one of product/service"
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a line naming neither a product nor a service is rejected")
    void lineWithNeitherIsRejected() throws Exception {
        Fixture f = fixture(0);
        assertThat(postOrderStatus(f, """
                {"quantity":1,"unitPrice":10}
                """)).isEqualTo(400);
    }

    @Test
    @DisplayName("a line naming both a product and a service is rejected")
    void lineWithBothIsRejected() throws Exception {
        Fixture f = fixture(0);
        // Left unchecked, whichever id the code read first would silently decide whether stock moves.
        assertThat(postOrderStatus(f, """
                {"productId":%d,"serviceId":%d,"quantity":1,"unitPrice":10}
                """.formatted(f.productId(), f.serviceId()))).isEqualTo(400);
    }

    // -------------------------------------------------------------------------------------------------
    // what the rest of the app sees
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the order detail names the service on its line, and leaves the product fields null")
    void orderDetailNamesTheService() throws Exception {
        Fixture f = fixture(0);
        long orderId = created(f.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"serviceId":%d,"quantity":2,"unitPrice":45}]}
                """.formatted(f.clientId(), f.warehouseId(), f.serviceId()));

        JsonNode line = readJson(mvc.perform(authed(get("/api/sales-orders/" + orderId + "/details"), f.owner()))
                .andReturn()).path("items").get(0);

        assertThat(line.path("serviceName").asText()).isEqualTo("Installation");
        assertThat(line.path("serviceId").asLong()).isEqualTo(f.serviceId());
        assertThat(line.path("productId").isNull()).as("no product on a service line").isTrue();
        assertThat(line.path("productName").isNull()).isTrue();
    }

    @Test
    @DisplayName("an invoice freezes the service's name and code into the line it issues")
    void invoiceSnapshotsTheServiceNameAndCode() throws Exception {
        Fixture f = fixture(0);
        long orderId = created(f.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"serviceId":%d,"quantity":2,"unitPrice":45}]}
                """.formatted(f.clientId(), f.warehouseId(), f.serviceId()));

        JsonNode invoice = readJson(mvc.perform(authed(post("/api/sales-orders/" + orderId + "/invoice"), f.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"type":"FINAL"}
                         """)).andReturn());

        JsonNode line = invoice.path("items").get(0);
        // productName/sku are display text on an invoice, not a product reference - see InvoiceItem.
        assertThat(line.path("productName").asText()).isEqualTo("Installation");
        assertThat(line.path("sku").asText()).isEqualTo("INST-1");
        assertThat(line.path("productId").isNull()).isTrue();
    }

    @Test
    @DisplayName("a service's detail page counts the orders it was sold on")
    void serviceDetailsReportSales() throws Exception {
        Fixture f = fixture(0);
        created(f.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"serviceId":%d,"quantity":3,"unitPrice":45}]}
                """.formatted(f.clientId(), f.warehouseId(), f.serviceId()));

        JsonNode details = readJson(mvc.perform(
                authed(get("/api/services/" + f.serviceId() + "/details"), f.owner())).andReturn());

        assertThat(details.path("summary").path("totalUnitsSold").asInt()).isEqualTo(3);
        assertThat(details.path("summary").path("salesOrderCount").asInt()).isEqualTo(1);
        assertThat(details.path("salesOrders")).hasSize(1);
    }

    // -------------------------------------------------------------------------------------------------
    // recurrenceMonths
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("recurrenceMonths round-trips through create and update, and stays null for a one-time service")
    void recurrenceMonthsRoundTrips() throws Exception {
        Tenant owner = newBusiness();

        JsonNode created = readJson(mvc.perform(authed(post("/api/services"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"Oil Change","price":45,"recurrenceMonths":6}
                         """)).andReturn());
        long serviceId = created.path("id").asLong();
        assertThat(created.path("recurrenceMonths").asInt()).isEqualTo(6);

        JsonNode updated = readJson(mvc.perform(authed(put("/api/services/" + serviceId), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"Oil Change","price":45,"recurrenceMonths":12}
                         """)).andReturn());
        assertThat(updated.path("recurrenceMonths").asInt()).isEqualTo(12);

        JsonNode oneTime = readJson(mvc.perform(authed(post("/api/services"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"Installation","price":45}
                         """)).andReturn());
        assertThat(oneTime.path("recurrenceMonths").isNull()).as("one-time service has no recurrence").isTrue();
    }

    @Test
    @DisplayName("a non-positive recurrenceMonths is rejected")
    void recurrenceMonthsMustBePositive() throws Exception {
        Tenant owner = newBusiness();
        int status = mvc.perform(authed(post("/api/services"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"Bad","price":45,"recurrenceMonths":0}
                         """)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    // -------------------------------------------------------------------------------------------------

    private long created(Tenant tenant, String path, String body) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("id").asLong();
    }

    /** Posts a one-line sales order and returns the HTTP status, for the validation cases. */
    private int postOrderStatus(Fixture f, String itemJson) throws Exception {
        return mvc.perform(authed(post("/api/sales-orders"), f.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW","items":[%s]}
                         """.formatted(f.clientId(), f.warehouseId(), itemJson)))
                .andReturn().getResponse().getStatus();
    }

    private int stock(Fixture f) throws Exception {
        return readJson(mvc.perform(authed(get("/api/products/" + f.productId()), f.owner())).andReturn())
                .path("stockQuantity").asInt();
    }

    private int setStatus(Tenant tenant, long orderId, String status) throws Exception {
        return mvc.perform(authed(patch("/api/sales-orders/" + orderId + "/status"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"status":"%s"}
                         """.formatted(status))).andReturn().getResponse().getStatus();
    }
}
