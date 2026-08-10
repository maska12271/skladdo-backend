package com.example.kladdo.service;

import com.example.kladdo.model.Company;
import com.example.kladdo.model.Manufacturer;
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
        c.setName("Kladdo OU");
        return renderer.tokensFor(m, null, c);
    }

    @Test
    void substitutesKnownTokens() {
        String out = renderer.render("Hi {{manufacturer.name}} in {{manufacturer.country}}", tokens());
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
        Map<String, String> tokens = renderer.tokensFor(m, null, new Company());
        String out = renderer.render("Name: {{manufacturer.name}}", tokens);
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
        Map<String, String> tokens = renderer.tokensFor(m, null, new Company());
        assertEquals("Quote for Smith & Sons", renderer.renderPlain("Quote for {{manufacturer.name}}", tokens));
        // While the HTML body path still escapes it.
        assertTrue(renderer.render("{{manufacturer.name}}", tokens).contains("&amp;"));
    }
}
