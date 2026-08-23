package com.example.skladdo.controller;

import com.example.skladdo.support.ApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The S3-backed upload/storage path: upload -> presign -> fetch, and the authenticated purchase-order
 * invoice-file proxy download. Runs against the real LocalStack S3 (see TestcontainersConfiguration),
 * not a mock, so a break in the AWS SDK wiring itself (bucket/region/credentials/path-style) shows up
 * here rather than only in a deployed environment.
 */
class StorageIntegrationTest extends ApiTestBase {

    @Test
    @DisplayName("an uploaded image's key presigns to a URL that actually serves the same bytes")
    void uploadThenPresignRoundTrips() throws Exception {
        Tenant owner = newBusiness();
        byte[] bytes = { 1, 2, 3, 4, 5 };

        JsonNode uploadResponse = readJson(mvc.perform(multipart("/api/upload/image")
                .file(new MockMultipartFile("file", "logo.png", "image/png", bytes))
                .header("Authorization", "Bearer " + owner.token())).andReturn());
        String key = uploadResponse.path("key").asText();
        assertThat(key).startsWith("images/").endsWith(".png");

        JsonNode presignResponse = readJson(
                mvc.perform(authed(get("/api/upload/presign?key=" + key), owner)).andReturn());
        String url = presignResponse.path("url").asText();
        assertThat(url).startsWith("http");

        HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("upload endpoints require authentication")
    void uploadRequiresAuthentication() throws Exception {
        int status = mvc.perform(multipart("/api/upload/image")
                        .file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{ 1 })))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("uploading a document with an unsupported content type is rejected")
    void uploadDocumentRejectsUnsupportedType() throws Exception {
        Tenant owner = newBusiness();
        int status = mvc.perform(multipart("/api/upload/document")
                        .file(new MockMultipartFile("file", "malware.exe", "application/x-msdownload", new byte[]{ 1 }))
                        .header("Authorization", "Bearer " + owner.token()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);
    }

    @Test
    @DisplayName("a purchase order's attached invoice file downloads through the authenticated proxy, byte for byte")
    void purchaseOrderInvoiceFileDownloadsFromS3() throws Exception {
        Tenant owner = newBusiness();
        long manufacturerId = created(owner, "/api/manufacturers", """
                {"name":"Storage Test Manufacturer"}
                """);
        long categoryId = created(owner, "/api/categories", """
                {"name":"Storage Test Category"}
                """);
        long warehouseId = created(owner, "/api/warehouses", """
                {"name":"Storage Test Depot"}
                """);
        long productId = created(owner, "/api/products", """
                {"name":"Storage Test Product","price":10,"manufacturer":{"id":%d},"category":{"id":%d}}
                """.formatted(manufacturerId, categoryId));
        long orderId = created(owner, "/api/purchase-orders", """
                {"manufacturerId":%d,"warehouseId":%d,"deliveryPrice":0,"status":"NEW",
                 "items":[{"productId":%d,"quantity":1,"unitPrice":10}]}
                """.formatted(manufacturerId, warehouseId, productId));

        byte[] pdfBytes = "%PDF-1.4 fake invoice".getBytes();
        JsonNode uploadResponse = readJson(mvc.perform(multipart("/api/upload/document")
                .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", pdfBytes))
                .header("Authorization", "Bearer " + owner.token())).andReturn());
        String key = uploadResponse.path("key").asText();

        mvc.perform(authed(put("/api/purchase-orders/" + orderId + "/invoice-file"), owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"invoiceFileKey":"%s","invoiceFileName":"invoice.pdf"}
                         """.formatted(key)));

        MvcResult result = mvc.perform(authed(get("/api/purchase-orders/" + orderId + "/invoice-file"), owner))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(pdfBytes);
        assertThat(result.getResponse().getContentType()).startsWith("application/pdf");
    }

    private long created(Tenant tenant, String path, String body) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("id").asLong();
    }
}
