package com.example.skladdo.service;

import com.example.skladdo.model.Client;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.Manufacturer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for token substitution. Confirms the whitelist behaviour and HTML-escaping of values. Pure
 * logic, no Spring context.
 */
class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    private Map<String, String> tokens() {
        Manufacturer m = new Manufacturer();
        m.setName("Acme Ltd");
        m.setAddress("1 Main St");
        m.setEmail("hello@acme.example");
        m.setPhone("+372 1234567");
        m.setCountry("Estonia");
        Company c = new Company();
        c.setName("Skladdo OU");
        return renderer.tokensFor(EmailRecipient.of(m), null, null, c);
    }

    @Test
    void substitutesKnownTokens() {
        String out = renderer.render("Hi {{recipient.name}} in {{recipient.country}}", tokens());
        assertEquals("Hi Acme Ltd in Estonia", out);
    }

    @Test
    void leavesUnknownTokensLiteral() {
        String out = renderer.render("Value: {{unknown.token}}", tokens());
        assertEquals("Value: {{unknown.token}}", out);
    }

    @Test
    void escapesHtmlInValues() {
        Manufacturer m = new Manufacturer();
        m.setName("<script>alert('x')</script> & Co");
        Map<String, String> tokens = renderer.tokensFor(EmailRecipient.of(m), null, null, new Company());
        String out = renderer.render("Name: {{recipient.name}}", tokens);
        assertFalse(out.contains("<script>"), "raw markup must not survive substitution");
        assertTrue(out.contains("&lt;script&gt;"));
        assertTrue(out.contains("&amp; Co"));
    }

    @Test
    void todayTokenIsIsoDate() {
        String out = renderer.render("{{today}}", tokens());
        assertEquals(LocalDate.now().toString(), out);
    }

    @Test
    void renderPlainDoesNotEscape() {
        // The subject line is plain text, so an ampersand in a value must stay literal, not become &amp;.
        Manufacturer m = new Manufacturer();
        m.setName("Smith & Sons");
        Map<String, String> tokens = renderer.tokensFor(EmailRecipient.of(m), null, null, new Company());
        assertEquals("Quote for Smith & Sons", renderer.renderPlain("Quote for {{recipient.name}}", tokens));
        // While the HTML body path still escapes it.
        assertTrue(renderer.render("{{recipient.name}}", tokens).contains("&amp;"));
    }

    @Test
    void resolvesTheSameTokensForAClient() {
        // The point of the generic token set: one template works for either side of the address book.
        Client c = new Client();
        c.setName("Northwind OU");
        c.setCountry("Latvia");
        Map<String, String> tokens = renderer.tokensFor(EmailRecipient.of(c), null, null, new Company());
        assertEquals("Hi Northwind OU in Latvia",
                renderer.render("Hi {{recipient.name}} in {{recipient.country}}", tokens));
    }

    @Test
    void legacyManufacturerTokensStillRender() {
        // Templates written before clients were reachable must keep working - including, deliberately,
        // when one of them is now sent to a client.
        Client c = new Client();
        c.setName("Northwind OU");
        Map<String, String> tokens = renderer.tokensFor(EmailRecipient.of(c), null, null, new Company());
        assertEquals("Dear Northwind OU", renderer.render("Dear {{manufacturer.name}}", tokens));
    }

    @Test
    void contactNameFallsBackToThePartnerWhenNobodyIsNamed() {
        // The token exists to open a greeting, so it must never resolve to nothing - "Dear ," in front of
        // a customer is worse than being impersonal. Found by sending a real bulk email with no contact.
        assertEquals("Dear Acme Ltd", renderer.render("Dear {{recipient.contactName}}", tokens()));
    }

    @Test
    void contactNameUsesTheNamedPersonWhenThereIsOne() {
        Manufacturer m = new Manufacturer();
        m.setName("Acme Ltd");
        Map<String, String> tokens = renderer.tokensFor(EmailRecipient.of(m), "Anna", null, new Company());
        assertEquals("Dear Anna", renderer.render("Dear {{recipient.contactName}}", tokens));
    }
}
