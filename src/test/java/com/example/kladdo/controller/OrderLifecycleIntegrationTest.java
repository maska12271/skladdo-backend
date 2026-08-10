package com.example.kladdo.controller;

import com.example.kladdo.support.ApiTestBase;
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
 * Order lifecycle and its relationship with stock.
 *
 * <p>The invariant these tests exist to protect: <b>stock moves only when an order crosses the
 * {@code SHIPPED}/{@code CLOSED} boundary, and never when fulfilment progress is recorded.</b> Recording a
 * pick or a goods receipt is bookkeeping about work done, not a stock movement — if it ever started moving
 * stock, every fulfilled order would be counted twice and the derived stock ledger (which replays status
 * changes, not fulfilment) would silently disagree with reality.</p>
 *
 * <p>That is a data-corruption bug that would be invisible until someone reconciled a warehouse by hand,
 * which is why it is worth a test rather than a comment.</p>
 */
class OrderLifecycleIntegrationTest extends ApiTestBase {

    /** A company with the reference data an order needs, plus one product holding known stock. */
    private record Fixture(Tenant owner, long productId, long warehouseId, long clientId, long manufacturerId) {
    }

    private Fixture fixture(int startingStock) throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/categories", """
                                                            {"name":"Lifecycle Category"}
                                                            """);
        long manufacturerId = created(owner, "/api/manufacturers", """
                                                                   {"name":"Lifecycle Manufacturer"}
                                                                   """);
        long clientId = created(owner, "/api/clients", """
                                                       {"name":"Lifecycle Client"}
                                                       """);
        long warehouseId = created(owner, "/api/warehouses", """
                                                             {"name":"Lifecycle Depot"}
                                                             """);
        long productId = created(owner, "/api/products", """
                {"name":"Lifecycle Product","price":100,"manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));

        if (startingStock > 0) {
            mvc.perform(authed(post("/api/products/" + productId + "/adjustments"), owner)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                             {"warehouseId":%d,"quantityChange":%d,"note":"seed"}
                             """.formatted(warehouseId, startingStock)));
        }
        return new Fixture(owner, productId, warehouseId, clientId, manufacturerId);
    }

    // -------------------------------------------------------------------------------------------------
    // the stock boundary
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a sales order removes stock only on SHIPPED/CLOSED, and restores it on the way back")
    void salesOrderMovesStockOnlyAtTheBoundary() throws Exception {
        Fixture f = fixture(100);
        long orderId = createSalesOrder(f, 10);

        assertThat(stock(f)).as("NEW").isEqualTo(100);
        assertThat(setStatus(f.owner(), "sales-orders", orderId, "IN_PROGRESS")).isEqualTo(200);
        assertThat(stock(f)).as("IN_PROGRESS").isEqualTo(100);
        assertThat(setStatus(f.owner(), "sales-orders", orderId, "CONFIRMED")).isEqualTo(200);
        assertThat(stock(f)).as("CONFIRMED").isEqualTo(100);

        setStatus(f.owner(), "sales-orders", orderId, "SHIPPED");
        assertThat(stock(f)).as("SHIPPED issues stock").isEqualTo(90);

        setStatus(f.owner(), "sales-orders", orderId, "CONFIRMED");
        assertThat(stock(f)).as("stepping back restores it").isEqualTo(100);

        setStatus(f.owner(), "sales-orders", orderId, "CLOSED");
        assertThat(stock(f)).as("CLOSED issues stock again").isEqualTo(90);
    }

    @Test
    @DisplayName("a purchase order adds stock at the same boundary, in the opposite direction")
    void purchaseOrderAddsStockAtTheBoundary() throws Exception {
        Fixture f = fixture(100);
        long orderId = createPurchaseOrder(f, 25);

        assertThat(stock(f)).as("NEW").isEqualTo(100);
        setStatus(f.owner(), "purchase-orders", orderId, "CONFIRMED");
        assertThat(stock(f)).as("CONFIRMED").isEqualTo(100);

        setStatus(f.owner(), "purchase-orders", orderId, "SHIPPED");
        assertThat(stock(f)).as("SHIPPED receives stock").isEqualTo(125);

        setStatus(f.owner(), "purchase-orders", orderId, "CONFIRMED");
        assertThat(stock(f)).as("stepping back reverses it").isEqualTo(100);
    }

    @Test
    @DisplayName("re-setting the status it already has changes nothing and records nothing")
    void noOpStatusChangeIsIgnored() throws Exception {
        Fixture f = fixture(100);
        long orderId = createSalesOrder(f, 10);
        setStatus(f.owner(), "sales-orders", orderId, "SHIPPED");
        int stockBefore = stock(f);
        int historyBefore = details(f.owner(), "sales-orders", orderId).path("statusHistory").size();

        setStatus(f.owner(), "sales-orders", orderId, "SHIPPED");

        assertThat(stock(f)).as("stock not moved twice").isEqualTo(stockBefore);
        assertThat(details(f.owner(), "sales-orders", orderId).path("statusHistory").size())
                .as("no duplicate history row").isEqualTo(historyBefore);
    }

    // -------------------------------------------------------------------------------------------------
    // the invariant
    // -------------------------------------------------------------------------------------------------

    /**
     * If this ever fails, stock is being double-counted: the status transition moves it and the pick moves
     * it again.
     */
    @Test
    @DisplayName("recording a pick never moves stock, in any status")
    void pickingNeverMovesStock() throws Exception {
        Fixture f = fixture(100);
        long orderId = createSalesOrder(f, 10);
        long lineId = firstLineId(f.owner(), "sales-orders", orderId);

        pick(f.owner(), "sales-orders", orderId, lineId, 4);
        assertThat(stock(f)).as("partial pick while NEW").isEqualTo(100);
        pick(f.owner(), "sales-orders", orderId, lineId, 10);
        assertThat(stock(f)).as("full pick while NEW").isEqualTo(100);
        pick(f.owner(), "sales-orders", orderId, lineId, 0);
        assertThat(stock(f)).as("un-picking").isEqualTo(100);

        setStatus(f.owner(), "sales-orders", orderId, "SHIPPED");
        assertThat(stock(f)).as("only the transition moves it").isEqualTo(90);

        pick(f.owner(), "sales-orders", orderId, lineId, 10);
        assertThat(stock(f)).as("picking again after shipping must not move it a second time").isEqualTo(90);
    }

    @Test
    @DisplayName("recording a goods receipt never moves stock")
    void receivingNeverMovesStock() throws Exception {
        Fixture f = fixture(100);
        long orderId = createPurchaseOrder(f, 25);
        long lineId = firstLineId(f.owner(), "purchase-orders", orderId);

        receive(f.owner(), orderId, lineId, 25);
        assertThat(stock(f)).as("full receipt recorded").isEqualTo(100);

        setStatus(f.owner(), "purchase-orders", orderId, "SHIPPED");
        assertThat(stock(f)).as("only the transition moves it").isEqualTo(125);

        receive(f.owner(), orderId, lineId, 25);
        assertThat(stock(f)).as("re-recording must not add it twice").isEqualTo(125);
    }

    @Test
    @DisplayName("completing a pick does not advance the order status")
    void pickingDoesNotAdvanceStatus() throws Exception {
        Fixture f = fixture(100);
        long orderId = createSalesOrder(f, 10);
        long lineId = firstLineId(f.owner(), "sales-orders", orderId);

        pick(f.owner(), "sales-orders", orderId, lineId, 10);

        // Shipping moves stock, so it stays a human decision.
        assertThat(details(f.owner(), "sales-orders", orderId).path("status").asText()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("over-receiving is allowed, because that discrepancy is what a goods receipt exists to show")
    void overReceiptIsAllowed() throws Exception {
        Fixture f = fixture(100);
        long orderId = createPurchaseOrder(f, 25);
        long lineId = firstLineId(f.owner(), "purchase-orders", orderId);

        receive(f.owner(), orderId, lineId, 30);

        assertThat(firstLineFulfilled(f.owner(), "purchase-orders", orderId)).isEqualTo(30);
        assertThat(stock(f)).as("and still moves no stock").isEqualTo(100);
    }

    @Test
    @DisplayName("a line id from another order is ignored rather than written")
    void foreignLineIdsAreIgnored() throws Exception {
        Fixture f = fixture(100);
        long orderA = createSalesOrder(f, 10);
        long orderB = createSalesOrder(f, 5);
        long lineB = firstLineId(f.owner(), "sales-orders", orderB);

        // Address order B's line through order A's endpoint.
        pick(f.owner(), "sales-orders", orderA, lineB, 3);

        assertThat(firstLineFulfilled(f.owner(), "sales-orders", orderB))
                .as("order B untouched").isZero();
    }

    // -------------------------------------------------------------------------------------------------
    // who may fulfil
    // -------------------------------------------------------------------------------------------------

    /**
     * Warehouse staff fulfil orders they are not allowed to edit, so the endpoint accepts either grant.
     * Verified from both directions so neither half can be dropped unnoticed.
     */
    @Test
    @DisplayName("fulfilment accepts ORDERS-edit or INVENTORY-create, and refuses neither")
    void fulfilmentPermissionRule() throws Exception {
        Fixture f = fixture(100);
        long orderId = createSalesOrder(f, 10);
        long lineId = firstLineId(f.owner(), "sales-orders", orderId);

        Tenant ordersEditor = inviteUser(f.owner(), "USER");
        setPermissions(f.owner(), ordersEditor.userId(), grant("SALES_ORDERS", true, false, true, false));
        Tenant stockKeeper = inviteUser(f.owner(), "USER");
        setPermissions(f.owner(), stockKeeper.userId(), grant("INVENTORY", true, true, false, false));
        Tenant outsider = inviteUser(f.owner(), "USER");
        setPermissions(f.owner(), outsider.userId(), grant("CLIENTS", true, false, false, false));

        assertThat(pickStatus(ordersEditor, orderId, lineId)).as("can edit orders").isEqualTo(200);
        assertThat(pickStatus(stockKeeper, orderId, lineId)).as("can post inventory").isEqualTo(200);
        assertThat(pickStatus(outsider, orderId, lineId)).as("neither grant").isEqualTo(403);
    }

    /**
     * The asymmetry is the point (finding N-005). Picking is capped at what the customer ordered — you
     * cannot pick eleven of ten — while a goods receipt deliberately accepts more than was ordered, because
     * recording an over-delivery is exactly what a receipt is for. The two methods were once identical, so
     * this pins the difference rather than letting a future tidy-up "harmonise" them back together.
     */
    @Test
    @DisplayName("picking is capped at the ordered quantity; a goods receipt still accepts over-delivery")
    void pickingIsCappedButReceivingIsNot() throws Exception {
        Fixture f = fixture(100);

        long salesId = createSalesOrder(f, 10);
        long salesLine = firstLineId(f.owner(), "sales-orders", salesId);
        pick(f.owner(), "sales-orders", salesId, salesLine, 999);
        assertThat(firstLineFulfilled(f.owner(), "sales-orders", salesId))
                .as("capped at the 10 ordered").isEqualTo(10);

        long purchaseId = createPurchaseOrder(f, 10);
        long purchaseLine = firstLineId(f.owner(), "purchase-orders", purchaseId);
        receive(f.owner(), purchaseId, purchaseLine, 12);
        assertThat(firstLineFulfilled(f.owner(), "purchase-orders", purchaseId))
                .as("over-delivery is recorded, not clamped").isEqualTo(12);

        // And the invariant this whole class exists for still holds: neither call moved stock.
        assertThat(stock(f)).isEqualTo(100);
    }

    @Test
    @DisplayName("a line id that is not on the order is rejected, not silently ignored")
    void unknownLineIdIsRejected() throws Exception {
        Fixture f = fixture(100);
        long salesId = createSalesOrder(f, 10);
        long purchaseId = createPurchaseOrder(f, 10);

        assertThat(mvc.perform(authed(put("/api/sales-orders/" + salesId + "/fulfilment"), f.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"lines":[{"lineId":999999,"quantity":1}]}
                         """)).andReturn().getResponse().getStatus()).as("sales").isEqualTo(400);

        assertThat(mvc.perform(authed(put("/api/purchase-orders/" + purchaseId + "/receipt"), f.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"lines":[{"lineId":999999,"quantity":1}]}
                         """)).andReturn().getResponse().getStatus()).as("purchase").isEqualTo(400);
    }

    // -------------------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------------------

    private long created(Tenant tenant, String path, String body) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("id").asLong();
    }

    private long createSalesOrder(Fixture f, int quantity) throws Exception {
        return created(f.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"productId":%d,"quantity":%d,"unitPrice":100}]}
                """.formatted(f.clientId(), f.warehouseId(), f.productId(), quantity));
    }

    private long createPurchaseOrder(Fixture f, int quantity) throws Exception {
        return created(f.owner(), "/api/purchase-orders", """
                {"manufacturerId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"productId":%d,"quantity":%d,"unitPrice":50}]}
                """.formatted(f.manufacturerId(), f.warehouseId(), f.productId(), quantity));
    }

    private int stock(Fixture f) throws Exception {
        return readJson(mvc.perform(authed(get("/api/products/" + f.productId()), f.owner())).andReturn())
                .path("stockQuantity").asInt();
    }

    private int setStatus(Tenant tenant, String type, long orderId, String status) throws Exception {
        return mvc.perform(authed(patch("/api/" + type + "/" + orderId + "/status"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"status":"%s"}
                         """.formatted(status))).andReturn().getResponse().getStatus();
    }

    private JsonNode details(Tenant tenant, String type, long orderId) throws Exception {
        return readJson(mvc.perform(authed(get("/api/" + type + "/" + orderId + "/details"), tenant)).andReturn());
    }

    private long firstLineId(Tenant tenant, String type, long orderId) throws Exception {
        return details(tenant, type, orderId).path("items").get(0).path("lineId").asLong();
    }

    private int firstLineFulfilled(Tenant tenant, String type, long orderId) throws Exception {
        return details(tenant, type, orderId).path("items").get(0).path("fulfilledQuantity").asInt();
    }

    private void pick(Tenant tenant, String type, long orderId, long lineId, int quantity) throws Exception {
        mvc.perform(authed(put("/api/" + type + "/" + orderId + "/fulfilment"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"lines":[{"lineId":%d,"quantity":%d}]}
                         """.formatted(lineId, quantity)));
    }

    private void receive(Tenant tenant, long orderId, long lineId, int quantity) throws Exception {
        mvc.perform(authed(put("/api/purchase-orders/" + orderId + "/receipt"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"lines":[{"lineId":%d,"quantity":%d}]}
                         """.formatted(lineId, quantity)));
    }

    private int pickStatus(Tenant tenant, long orderId, long lineId) throws Exception {
        return mvc.perform(authed(put("/api/sales-orders/" + orderId + "/fulfilment"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"lines":[{"lineId":%d,"quantity":2}]}
                         """.formatted(lineId))).andReturn().getResponse().getStatus();
    }
}
