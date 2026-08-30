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

/* Helpers mirror the other controller tests: `created` posts and returns the new id, `statusOf` runs a
 * request and reports the status code. */

/**
 * Deleting a category that is still in use.
 *
 * <p>The invariant: <b>deleting a category never deletes what was filed under it.</b> Deletion used to be
 * refused outright by the database's foreign key the moment a single product referenced the category, and
 * the user was told the action "conflicts with existing data" with no way forward short of re-filing every
 * product by hand. A category is a label rather than the thing itself, so it now un-files its members and
 * removes itself.</p>
 *
 * <p>The column that used to guarantee a product always had a category ({@code category_id NOT NULL}) is
 * exactly what had to be relaxed to allow this, so the guarantee that the products survive now lives in
 * code and needs a test to hold it.</p>
 */
class CategoryDeletionIntegrationTest extends ApiTestBase {

    @Test
    @DisplayName("deleting a product category keeps its products and leaves them uncategorised")
    void deletingProductCategoryUnfilesItsProducts() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/categories", """
                                                            {"name":"Doomed Category"}
                                                            """);
        long manufacturerId = created(owner, "/api/manufacturers", """
                                                                   {"name":"Some Manufacturer"}
                                                                   """);
        long productId = created(owner, "/api/products", """
                {"name":"Filed Product","price":10,"manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));

        assertThat(statusOf(delete("/api/categories/" + categoryId), owner))
                .as("deleting an in-use category is allowed").isEqualTo(200);

        JsonNode product = readJson(mvc.perform(authed(get("/api/products/" + productId), owner)).andReturn());
        assertThat(product.path("name").asText()).as("the product itself survives").isEqualTo("Filed Product");
        assertThat(product.path("category").isNull() || product.path("category").isMissingNode())
                .as("but it is no longer in any category").isTrue();
    }

    @Test
    @DisplayName("deleting a service category keeps its services and leaves them uncategorised")
    void deletingServiceCategoryUnfilesItsServices() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/service-categories", """
                                                                    {"name":"Doomed Service Category"}
                                                                    """);
        long serviceId = created(owner, "/api/services", """
                {"name":"Filed Service","code":"SVC-1","price":45,"category":{"id":%d}}
                """.formatted(categoryId));

        assertThat(statusOf(delete("/api/service-categories/" + categoryId), owner)).isEqualTo(200);

        JsonNode service = readJson(mvc.perform(authed(get("/api/services/" + serviceId), owner)).andReturn());
        assertThat(service.path("name").asText()).isEqualTo("Filed Service");
        assertThat(service.path("category").isNull() || service.path("category").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("deleting a manufacturer category untags its manufacturers")
    void deletingPartnerCategoryUntagsManufacturers() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/partner-categories", """
                                                                    {"name":"Doomed Tag"}
                                                                    """);
        long manufacturerId = created(owner, "/api/manufacturers", """
                {"name":"Tagged Manufacturer","categories":[{"id":%d}]}
                """.formatted(categoryId));

        assertThat(statusOf(delete("/api/partner-categories/" + categoryId), owner)).isEqualTo(200);

        JsonNode manufacturer = readJson(
                mvc.perform(authed(get("/api/manufacturers/" + manufacturerId), owner)).andReturn());
        assertThat(manufacturer.path("name").asText()).isEqualTo("Tagged Manufacturer");
        assertThat(manufacturer.path("categories")).as("the tag is gone, the manufacturer is not").isEmpty();
    }

    @Test
    @DisplayName("creating a product without a category is still a plain 400, not a server error")
    void productStillRequiresACategoryOnCreate() throws Exception {
        Tenant owner = newBusiness();
        long manufacturerId = created(owner, "/api/manufacturers", """
                                                                   {"name":"Another Manufacturer"}
                                                                   """);

        // The column is nullable now, which cost the field its @NotNull. Nothing should be able to create an
        // uncategorised product through the API on purpose — and an omitted category must not become a 500.
        int status = statusOf(post("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"No Category","price":10,"manufacturer":{"id":%d}}
                         """.formatted(manufacturerId)), owner);
        assertThat(status).isEqualTo(400);
    }

    @Test
    @DisplayName("an uncategorised product can still be stocked and edited")
    void uncategorisedProductRemainsUsable() throws Exception {
        Tenant owner = newBusiness();
        long categoryId = created(owner, "/api/categories", """
                                                            {"name":"Temporary Category"}
                                                            """);
        long manufacturerId = created(owner, "/api/manufacturers", """
                                                                   {"name":"Stocking Manufacturer"}
                                                                   """);
        long warehouseId = created(owner, "/api/warehouses", """
                                                             {"name":"Orphan Depot"}
                                                             """);
        long productId = created(owner, "/api/products", """
                {"name":"Orphan Product","price":10,"manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));
        assertThat(statusOf(delete("/api/categories/" + categoryId), owner)).isEqualTo(200);

        // The product is re-saved through the same path a new one is created through, so a category check
        // that did not distinguish the two would strand every product whose category had been deleted.
        int status = statusOf(post("/api/products/" + productId + "/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"warehouseId":%d,"quantityChange":5,"note":"stocking an uncategorised product"}
                         """.formatted(warehouseId)), owner);
        assertThat(status).as("stock adjustment on an uncategorised product").isBetween(200, 299);
    }

    private long created(Tenant tenant, String path, String body) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("id").asLong();
    }
}
