package com.example.kladdo.service;

import com.example.kladdo.model.Company;
import com.example.kladdo.model.Manufacturer;
import com.example.kladdo.security.CustomUserDetails;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Substitutes {@code {{token}}} placeholders in a user-authored email subject/body with a fixed
 * whitelist of manufacturer / sender / company values.
 *
 * <p>Deliberately <em>not</em> Thymeleaf or SpEL: this template text is authored by tenant admins and
 * treated as untrusted for expression purposes, so it must never be evaluated as an expression language
 * (that would be an injection vector). The invoice PDF templates are different - those are fixed files
 * shipped by developers. Substitution is a literal find/replace over a closed token set; token
 * <em>values</em> are HTML-escaped (manufacturer data can contain angle brackets/ampersands), while the
 * surrounding template HTML is trusted as authored.</p>
 */
@Service
public class EmailTemplateRenderer {

    /**
     * Builds the whitelist of {@code token key -> raw value} for a send. Only these keys are ever
     * substituted; any other {@code {{...}}} text is left untouched as literal text.
     */
    public Map<String, String> tokensFor(Manufacturer manufacturer, CustomUserDetails sender, Company company) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("manufacturer.name", nz(manufacturer.getName()));
        tokens.put("manufacturer.address", nz(manufacturer.getAddress()));
        tokens.put("manufacturer.email", nz(manufacturer.getEmail()));
        tokens.put("manufacturer.phone", nz(manufacturer.getPhone()));
        tokens.put("manufacturer.country", nz(manufacturer.getCountry()));
        tokens.put("sender.fullName", sender != null ? nz(sender.getFullName()) : "");
        tokens.put("company.name", company != null ? nz(company.getName()) : "");
        tokens.put("today", LocalDate.now().toString());
        return tokens;
    }

    /**
     * Replaces each {@code {{key}}} in {@code templateText} with the HTML-escaped value from
     * {@code tokenValues}. For HTML contexts (the email body). Unknown tokens are left as-is. Uses literal
     * {@link String#replace} (not {@code replaceAll}) so {@code $}/{@code \} in a value are never treated
     * as replacement specials.
     */
    public String render(String templateText, Map<String, String> tokenValues) {
        return substitute(templateText, tokenValues, true);
    }

    /**
     * Like {@link #render} but without HTML-escaping - for plain-text contexts such as the email subject
     * line, where entities like {@code &amp;} would show up literally in the header.
     */
    public String renderPlain(String templateText, Map<String, String> tokenValues) {
        return substitute(templateText, tokenValues, false);
    }

    private String substitute(String templateText, Map<String, String> tokenValues, boolean escape) {
        if (templateText == null || templateText.isEmpty()) {
            return templateText;
        }
        String result = templateText;
        for (Map.Entry<String, String> entry : tokenValues.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{{" + entry.getKey() + "}}", escape ? htmlEscape(value) : value);
        }
        return result;
    }

    private static String nz(String value) {
        return value != null ? value : "";
    }

    private static String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
