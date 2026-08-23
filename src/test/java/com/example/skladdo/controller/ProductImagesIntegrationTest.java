package com.example.skladdo.controller;

import com.example.skladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * {@code PUT /api/products/{id}/images}, the endpoint behind the detail page's gallery editor. What makes
 * it worth its own test is the promise it makes: it replaces the picture list and touches nothing else, so
 * adding a photo can never quietly rewrite a price the way a full-product round-trip could.
 */
class ProductImagesIntegrationTest extends ApiTestBase {

    @Test
    @DisplayName("replacing the images leaves every other field alone")
    void imagesAreReplacedInIsolation() throws Exception {
        Tenant owner = newBusiness();
        long productId = createProduct(owner, "Gallery Product", "images/original.jpg");

        JsonNode updated = readJson(mvc.perform(authed(put("/api/products/" + productId + "/images"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"imageKeys":["images/one.jpg","images/two.jpg"]}
                         """)).andReturn());

        assertThat(imageKeys(updated)).containsExactly("images/one.jpg", "images/two.jpg");

        JsonNode reloaded = readJson(mvc.perform(authed(get("/api/products/" + productId), owner)).andReturn());
        assertThat(imageKeys(reloaded)).as("persisted").containsExactly("images/one.jpg", "images/two.jpg");
        assertThat(reloaded.path("name").asText()).isEqualTo("Gallery Product");
        assertThat(reloaded.path("price").asDouble()).isEqualTo(12.5);
        assertThat(reloaded.path("minimumStock").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("an empty list clears the gallery")
    void emptyListClearsTheGallery() throws Exception {
        Tenant owner = newBusiness();
        long productId = createProduct(owner, "Cleared Product", "images/only.jpg");

        JsonNode updated = readJson(mvc.perform(authed(put("/api/products/" + productId + "/images"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"imageKeys":[]}
                         """)).andReturn());

        assertThat(imageKeys(updated)).isEmpty();
    }

    @Test
    @DisplayName("editing images needs the products edit permission, not just view")
    void editPermissionIsRequired() throws Exception {
        Tenant owner = newBusiness();
        long productId = createProduct(owner, "Guarded Product", "images/original.jpg");

        Tenant viewer = inviteUser(owner, "USER");
        setPermissions(owner, viewer.userId(), grant("PRODUCTS", true, false, false, false));

        assertThat(statusOf(put("/api/products/" + productId + "/images")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"imageKeys":["images/sneaky.jpg"]}
                         """), viewer)).isEqualTo(403);
    }

    // -------------------------------------------------------------------------------------------------

    private long createProduct(Tenant tenant, String name, String imageKey) throws Exception {
        long categoryId = createRelation(tenant, "/api/categories", name + " Category");
        long manufacturerId = createRelation(tenant, "/api/manufacturers", name + " Manufacturer");

        return readJson(mvc.perform(authed(post("/api/products"), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s","price":12.50,"minimumStock":7,"imageKeys":["%s"],
                          "manufacturer":{"id":%d},"category":{"id":%d}}
                         """.formatted(name, imageKey, manufacturerId, categoryId))).andReturn())
                .path("id").asLong();
    }

    private long createRelation(Tenant tenant, String path, String name) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"%s"}
                         """.formatted(name))).andReturn()).path("id").asLong();
    }

    private static List<String> imageKeys(JsonNode product) {
        List<String> keys = new ArrayList<>();
        product.path("imageKeys").forEach((node) -> keys.add(node.asText()));
        return keys;
    }
}
