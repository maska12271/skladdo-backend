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

/**
 * Invoice arithmetic and currency conversion — the places where a defect costs actual money rather than
 * merely annoying someone.
 *
 * <p>Two conventions are load-bearing here and are asserted explicitly, because both are the kind of thing
 * a well-meaning refactor "corrects" into a bug:</p>
 * <ul>
 *   <li>An order's {@code totalAmount} is <b>net</b> (subtotal + delivery); an invoice's total is
 *       <b>gross</b> (net + tax). A prepayment percentage applies to the gross.</li>
 *   <li>{@code exchangeRate} means <b>1 base = rate foreign</b>, so converting to base <b>divides</b>.
 *       Multiplying instead would inflate every foreign-currency figure, silently and plausibly.</li>
 * </ul>
 */
class MoneyIntegrationTest extends ApiTestBase {

    private record Shop(Tenant owner, long clientId, long warehouseId, long productId) {
    }

    private Shop shop() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/categories", "{\"name\":\"Money\"}");
        long manufacturerId = created(owner, "/api/manufacturers", "{\"name\":\"Money Maker\"}");
        long clientId = created(owner, "/api/clients", "{\"name\":\"Money Client\"}");
        long warehouseId = created(owner, "/api/warehouses", "{\"name\":\"Money Depot\"}");
        long productId = created(owner, "/api/products", """
                {"name":"Money Product","price":100,"manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));
        return new Shop(owner, clientId, warehouseId, productId);
    }

    // -------------------------------------------------------------------------------------------------
    // invoice arithmetic
    // -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an order total is net; the invoice adds tax to make it gross")
    void orderIsNetAndInvoiceIsGross() throws Exception {
        Shop s = shop();
        long orderId = order(s, 10, "100", "20");   // 10 x 100 @ 20% tax

        JsonNode order = readJson(mvc.perform(authed(get("/api/sales-orders/" + orderId), s.owner())).andReturn());
        assertThat(order.path("subtotalAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(order.path("taxAmount").decimalValue()).isEqualByComparingTo("200");
        assertThat(order.path("totalAmount").decimalValue()).as("order total is NET").isEqualByComparingTo("1000");

        JsonNode invoice = invoice(s.owner(), orderId, "{}");
        assertThat(invoice.path("totalAmount").decimalValue()).as("invoice total is GROSS").isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("a prepayment invoice is a proportional split of the gross, tax included")
    void prepaymentSplitsProportionally() throws Exception {
        Shop s = shop();
        long orderId = order(s, 10, "100", "20");

        JsonNode prepayment = invoice(s.owner(), orderId, """
                                                          {"type":"PREPAYMENT","prepaymentPercent":30}
                                                          """);

        assertThat(prepayment.path("type").asText()).isEqualTo("PREPAYMENT");
        // 30% of gross 1200 = 360, split as 300 net + 60 tax rather than 30% of the net only.
        assertThat(prepayment.path("subtotalAmount").decimalValue()).isEqualByComparingTo("300");
        assertThat(prepayment.path("taxAmount").decimalValue()).isEqualByComparingTo("60");
        assertThat(prepayment.path("totalAmount").decimalValue()).isEqualByComparingTo("360");
        assertThat(prepayment.path("amountDue").decimalValue()).isEqualByComparingTo("360");
    }

    @Test
    @DisplayName("a final invoice nets off a paid prepayment")
    void finalInvoiceNetsOffThePrepayment() throws Exception {
        Shop s = shop();
        long orderId = order(s, 10, "100", "20");
        JsonNode prepayment = invoice(s.owner(), orderId, """
                                                          {"type":"PREPAYMENT","prepaymentPercent":30}
                                                          """);

        // Only a *paid* prepayment is netted off.
        mvc.perform(authed(patch("/api/invoices/" + prepayment.path("id").asLong() + "/payment"), s.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"status":"PAID","paidDate":"2026-08-06"}
                         """));

        JsonNode settled = invoice(s.owner(), orderId, """
                                                       {"type":"FINAL"}
                                                       """);

        assertThat(settled.path("totalAmount").decimalValue()).isEqualByComparingTo("1200");
        assertThat(settled.path("appliedPrepaymentAmount").decimalValue()).isEqualByComparingTo("360");
        assertThat(settled.path("amountDue").decimalValue())
                .as("due is the balance, not the whole invoice").isEqualByComparingTo("840");
    }

    @Test
    @DisplayName("marking an invoice paid clears the amount due")
    void payingClearsTheBalance() throws Exception {
        Shop s = shop();
        long orderId = order(s, 10, "100", "20");
        JsonNode issued = invoice(s.owner(), orderId, "{}");
        assertThat(issued.path("amountDue").decimalValue()).isEqualByComparingTo("1200");

        JsonNode paid = readJson(mvc.perform(
                authed(patch("/api/invoices/" + issued.path("id").asLong() + "/payment"), s.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"status":"PAID","paidDate":"2026-08-06"}
                                 """)).andReturn());

        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.path("amountDue").decimalValue()).isEqualByComparingTo("0");
    }

    /**
     * A late-payment penalty accrues on what is still <em>owed</em>, not on the invoice's face value. Get
     * this wrong on a prepaid order and every overdue customer is over-charged — quietly, and in a figure
     * they are unlikely to recompute. The numbers are chosen so the two bases differ clearly: 10% of the
     * 840 principal is 84, 10% of the 1200 gross would be 120.
     */
    @Test
    @DisplayName("penalty accrues on the outstanding principal, not the gross total")
    void penaltyChargesThePrincipalNotTheGross() throws Exception {
        Shop s = shop();
        long orderId = order(s, 10, "100", "20");                    // gross 1200
        JsonNode prepayment = invoice(s.owner(), orderId, """
                                                          {"type":"PREPAYMENT","prepaymentPercent":30}
                                                          """);
        mvc.perform(authed(patch("/api/invoices/" + prepayment.path("id").asLong() + "/payment"), s.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"status":"PAID","paidDate":"2026-01-01"}
                         """));

        JsonNode overdue = invoice(s.owner(), orderId, """
                {"type":"FINAL","dueDate":"2026-01-31","penaltyPercent":10,"penaltyPeriod":"ONE_TIME"}
                """);
        JsonNode live = readJson(mvc.perform(
                authed(get("/api/invoices/" + overdue.path("id").asLong()), s.owner())).andReturn());

        assertThat(live.path("overdue").asBoolean()).isTrue();
        assertThat(live.path("penaltyAmount").decimalValue())
                .as("10% of the 840 principal; charging the 1200 gross would give 120")
                .isEqualByComparingTo("84");
        assertThat(live.path("amountDue").decimalValue())
                .as("principal plus penalty").isEqualByComparingTo("924");
    }

    /**
     * The dashboard recomputes receivables independently of the invoice endpoint, so the two can drift
     * apart. They are the same numbers to a user, so they are asserted together.
     */
    @Test
    @DisplayName("dashboard receivables agree with the invoice they describe")
    void receivablesAgreeWithTheInvoice() throws Exception {
        Shop s = shop();
        long orderId = order(s, 10, "100", "20");
        JsonNode prepayment = invoice(s.owner(), orderId, """
                                                          {"type":"PREPAYMENT","prepaymentPercent":30}
                                                          """);
        mvc.perform(authed(patch("/api/invoices/" + prepayment.path("id").asLong() + "/payment"), s.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"status":"PAID","paidDate":"2026-01-01"}
                         """));
        invoice(s.owner(), orderId, """
                {"type":"FINAL","dueDate":"2026-01-31","penaltyPercent":10,"penaltyPeriod":"ONE_TIME"}
                """);

        JsonNode receivables = readJson(mvc.perform(
                authed(get("/api/dashboard/stats"), s.owner())).andReturn()).path("receivables");

        assertThat(receivables.path("unpaidTotal").decimalValue()).isEqualByComparingTo("840");
        assertThat(receivables.path("overdueTotal").decimalValue()).isEqualByComparingTo("840");
        assertThat(receivables.path("penaltyAccruedTotal").decimalValue()).isEqualByComparingTo("84");
        assertThat(receivables.path("topOverdue").get(0).path("amountDue").decimalValue())
                .as("principal + penalty, matching the invoice").isEqualByComparingTo("924");
    }

    // -------------------------------------------------------------------------------------------------
    // currency conversion
    // -------------------------------------------------------------------------------------------------

    /**
     * The decisive test for conversion direction. The three orders are priced so that dividing, multiplying
     * and not converting at all give totals far apart (1050 / 6300 / 2300) — with a single order the first
     * two can coincide, which would let a flipped rate pass unnoticed.
     */
    @Test
    @DisplayName("foreign amounts are divided by the rate when rolled up to base currency")
    void rollUpsDivideByTheExchangeRate() throws Exception {
        Shop s = shop();
        orderInCurrency(s, 10, "USD", "2");   // 1000 USD @ 1 EUR = 2 USD -> 500
        orderInCurrency(s, 10, "GBP", "4");   // 1000 GBP @ 1 EUR = 4 GBP -> 250
        order(s, 3, "100", null);             //  300 EUR, no rate       -> 300

        JsonNode dashboard = readJson(mvc.perform(authed(get("/api/dashboard/stats"), s.owner())).andReturn());
        JsonNode monthly = dashboard.path("monthly");

        assertThat(dashboard.path("sales").path("thisMonth").decimalValue())
                .as("500 + 250 + 300; multiplying would give 6300, not converting 2300")
                .isEqualByComparingTo("1050");
        assertThat(monthly.get(monthly.size() - 1).path("revenue").decimalValue())
                .as("the chart must agree with the KPI above it")
                .isEqualByComparingTo("1050");
    }

    @Test
    @DisplayName("a currency the rate feed does not publish degrades to a manual rate, not an error")
    void unpublishedCurrencyDegradesGracefully() throws Exception {
        Tenant owner = newBusiness();

        // The base currency needs no lookup at all.
        JsonNode base = quote(owner, "EUR");
        assertThat(base.path("source").asText()).isEqualTo("SAME");
        assertThat(base.path("rate").decimalValue()).isEqualByComparingTo("1");

        // RUB was dropped by the ECB in 2022, and XYZ does not exist. Both must answer, not fail: the
        // form then asks the user for a rate instead of blocking them.
        for (String currency : new String[]{"RUB", "XYZ"}) {
            JsonNode unknown = quote(owner, currency);
            assertThat(unknown.path("source").asText()).as(currency).isEqualTo("NONE");
            assertThat(unknown.path("rate").decimalValue()).as(currency).isEqualByComparingTo("0");
        }
    }

    /**
     * Regression guard for F-013. The reorder view and the draft purchase order it creates are always in
     * base currency, but the product's most recent purchase may not have been - the query used to return
     * the raw foreign-currency {@code unitPrice} with nothing to convert it, so it was shown (and would be
     * reused) as if it were already base currency.
     */
    @Test
    @DisplayName("reorder suggestions convert the last purchase price to base currency")
    void reorderSuggestionConvertsLastPurchasePriceToBase() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/categories", "{\"name\":\"Reorder\"}");
        long manufacturerId = created(owner, "/api/manufacturers", "{\"name\":\"Reorder Maker\"}");
        long warehouseId = created(owner, "/api/warehouses", "{\"name\":\"Reorder Depot\"}");
        long productId = created(owner, "/api/products", """
                {"name":"Reorder Product","price":10,"minimumStock":5,
                 "manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));

        created(owner, "/api/purchase-orders", """
                {"manufacturerId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "currency":"USD","exchangeRate":2,
                 "items":[{"productId":%d,"quantity":1,"unitPrice":100,"lotNumber":"L1"}]}
                """.formatted(manufacturerId, warehouseId, productId));

        JsonNode suggestions = readJson(
                mvc.perform(authed(get("/api/products/reorder-suggestions"), owner)).andReturn());
        JsonNode row = null;
        for (JsonNode candidate : suggestions) {
            if (candidate.path("productId").asLong() == productId) {
                row = candidate;
                break;
            }
        }

        assertThat(row).as("product appears as low stock").isNotNull();
        assertThat(row.path("lastPurchasePrice").decimalValue())
                .as("100 USD at rate 2 -> 50 EUR base, not 100")
                .isEqualByComparingTo("50");
    }

    /**
     * Regression guard for F-015. Invoice arithmetic and currency conversion were each tested thoroughly in
     * Stage 4 but never crossed: every invoice test used a base-currency order and every currency test
     * stopped at the order. An invoice was therefore stamped with the company's base currency while keeping
     * the order's unconverted foreign amount, so a 1000 USD order printed as "1000.00 EUR" on the PDF - the
     * templates render {@code totalAmount + ' ' + currency} directly.
     */
    @Test
    @DisplayName("an invoice is denominated in its order's currency, not the company's")
    void invoiceCarriesTheOrdersCurrency() throws Exception {
        Shop s = shop();

        long foreign = created(s.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "currency":"USD","exchangeRate":2,
                 "items":[{"productId":%d,"quantity":10,"unitPrice":100}]}
                """.formatted(s.clientId(), s.warehouseId(), s.productId()));
        JsonNode foreignInvoice = invoice(s.owner(), foreign, "{}");

        assertThat(foreignInvoice.path("currency").asText())
                .as("billed in the currency the customer agreed to").isEqualTo("USD");
        assertThat(foreignInvoice.path("totalAmount").decimalValue())
                .as("the amount stays in that currency, unconverted").isEqualByComparingTo("1000");

        // A base-currency order is unaffected: no currency on the order still means the company's own.
        long domestic = order(s, 3, "100", null);
        JsonNode domesticInvoice = invoice(s.owner(), domestic, "{}");
        assertThat(domesticInvoice.path("currency").asText()).isEqualTo("EUR");
        assertThat(domesticInvoice.path("totalAmount").decimalValue()).isEqualByComparingTo("300");

        // And the roll-up still converts both to base correctly - 500 + 300, not 1000 + 300.
        JsonNode receivables = readJson(mvc.perform(
                authed(get("/api/dashboard/stats"), s.owner())).andReturn()).path("receivables");
        assertThat(receivables.path("unpaidTotal").decimalValue())
                .as("mixed-currency receivables").isEqualByComparingTo("800");
    }

    // -------------------------------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------------------------------

    private long created(Tenant tenant, String path, String body) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("id").asLong();
    }

    private long order(Shop s, int quantity, String unitPrice, String taxPercent) throws Exception {
        String tax = taxPercent == null ? "" : ",\"taxRatePercent\":" + taxPercent;
        return created(s.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"productId":%d,"quantity":%d,"unitPrice":%s%s}]}
                """.formatted(s.clientId(), s.warehouseId(), s.productId(), quantity, unitPrice, tax));
    }

    private void orderInCurrency(Shop s, int quantity, String currency, String rate) throws Exception {
        created(s.owner(), "/api/sales-orders", """
                {"clientId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "currency":"%s","exchangeRate":%s,
                 "items":[{"productId":%d,"quantity":%d,"unitPrice":100}]}
                """.formatted(s.clientId(), s.warehouseId(), currency, rate, s.productId(), quantity));
    }

    private JsonNode invoice(Tenant tenant, long orderId, String body) throws Exception {
        return readJson(mvc.perform(authed(post("/api/sales-orders/" + orderId + "/invoice"), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
    }

    private JsonNode quote(Tenant tenant, String currency) throws Exception {
        return readJson(mvc.perform(
                authed(get("/api/exchange-rates/quote?currency=" + currency), tenant)).andReturn());
    }
}
