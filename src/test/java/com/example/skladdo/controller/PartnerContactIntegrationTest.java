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
 * The named people at a client or a manufacturer.
 *
 * <p>Two invariants worth a test. First, <b>a partner with contacts can still be deleted</b>: the rows
 * hold the partner's id as a plain column with no foreign key, so nothing at the database level removes
 * them and the service has to — which it can only do inside a transaction. A partner with <em>no</em>
 * contacts hides that entirely (a derived delete over an empty result never calls {@code remove}), so a
 * test that does not create one first proves nothing.</p>
 *
 * <p>Second, <b>a contact is reachable only through the partner it belongs to</b>. The id is not a
 * capability on its own: addressing it under a different partner - or another company's - must 404
 * rather than read or write somebody else's record.</p>
 */
class PartnerContactIntegrationTest extends ApiTestBase {

    @Test
    @DisplayName("a client with contacts can still be deleted, and its contacts go with it")
    void deletingAClientRemovesItsContacts() throws Exception {
        Tenant owner = newBusiness();
        long clientId = created(owner, "/api/clients", """
                                                       {"name":"Contact Delete Client"}
                                                       """);
        created(owner, "/api/clients/" + clientId + "/contacts", """
                {"name":"Ada Buyer","position":"Purchasing","email":"ada@partner.example"}
                """);
        created(owner, "/api/clients/" + clientId + "/contacts", """
                {"name":"Grace Payer","position":"Accounts"}
                """);
        assertThat(contacts(owner, "/api/clients/" + clientId)).hasSize(2);

        assertThat(statusOf(delete("/api/clients/" + clientId), owner)).as("delete").isEqualTo(200);
        assertThat(statusOfGet("/api/clients/" + clientId, owner)).as("client gone").isEqualTo(404);
    }

    @Test
    @DisplayName("a manufacturer with contacts can still be deleted")
    void deletingAManufacturerRemovesItsContacts() throws Exception {
        Tenant owner = newBusiness();
        long manufacturerId = created(owner, "/api/manufacturers", """
                                                                   {"name":"Contact Delete Supplier"}
                                                                   """);
        created(owner, "/api/manufacturers/" + manufacturerId + "/contacts", """
                {"name":"Rein Seller","position":"Sales","email":"rein@supplier.example"}
                """);

        assertThat(statusOf(delete("/api/manufacturers/" + manufacturerId), owner)).as("delete").isEqualTo(200);
        assertThat(statusOfGet("/api/manufacturers/" + manufacturerId, owner)).as("gone").isEqualTo(404);
    }

    @Test
    @DisplayName("a contact is only reachable under the partner it belongs to")
    void aContactIsScopedToItsOwnPartner() throws Exception {
        Tenant owner = newBusiness();
        long mine = created(owner, "/api/manufacturers", """
                                                          {"name":"Contact Scope Mine"}
                                                          """);
        long other = created(owner, "/api/manufacturers", """
                                                           {"name":"Contact Scope Other"}
                                                           """);
        long contactId = created(owner, "/api/manufacturers/" + mine + "/contacts", """
                {"name":"Only Here","email":"only@here.example"}
                """);

        assertThat(statusOf(put("/api/manufacturers/" + other + "/contacts/" + contactId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                         {"name":"Moved"}
                         """), owner)).as("edit under the wrong partner").isEqualTo(404);
        assertThat(statusOf(delete("/api/manufacturers/" + other + "/contacts/" + contactId), owner))
                .as("delete under the wrong partner").isEqualTo(404);

        Tenant stranger = newBusiness();
        assertThat(statusOfGet("/api/manufacturers/" + mine + "/contacts", stranger))
                .as("another company's partner").isEqualTo(404);

        assertThat(contacts(owner, "/api/manufacturers/" + mine)).as("untouched").hasSize(1);
    }

    private JsonNode contacts(Tenant tenant, String partnerPath) throws Exception {
        return readJson(mvc.perform(authed(get(partnerPath + "/contacts"), tenant)).andReturn());
    }

    private long created(Tenant tenant, String path, String body) throws Exception {
        return readJson(mvc.perform(authed(post(path), tenant)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()).path("id").asLong();
    }
}
