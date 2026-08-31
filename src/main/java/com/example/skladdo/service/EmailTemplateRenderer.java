package com.example.skladdo.service;

import com.example.skladdo.model.Company;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Substitutes {@code {{token}}} placeholders in a user-authored email subject/body with a fixed
 * whitelist of recipient / sender / company values.
 *
 * <p>Deliberately <em>not</em> Thymeleaf or SpEL: this template text is authored by tenant admins and
 * treated as untrusted for expression purposes, so it must never be evaluated as an expression language
 * (that would be an injection vector). The invoice PDF templates are different - those are fixed files
 * shipped by developers. Substitution is a literal find/replace over a closed token set; token
 * <em>values</em> are HTML-escaped (partner data can contain angle brackets/ampersands), while the
 * surrounding template HTML is trusted as authored.</p>
 */
@Service
public class EmailTemplateRenderer {

    /**
     * Builds the whitelist of {@code token key -> raw value} for a send. Only these keys are ever
     * substituted; any other {@code {{...}}} text is left untouched as literal text.
     *
     * <p>{@code recipient.*} is the canonical set, and works whether the email is going to a client or a
     * manufacturer. The {@code manufacturer.*} keys are legacy aliases carrying the same values, kept so
     * templates written before clients became reachable keep rendering - including when one of them is
     * now sent to a client. There is deliberately no {@code client.*} alias: it would resolve on a
     * manufacturer send too, which reads as a bug rather than a convenience.</p>
     *
     * <p>{@code senderFullName} is passed in rather than read from the security context, because the
     * scheduled-send path runs on a background thread that has none.</p>
     */
    public Map<String, String> tokensFor(EmailRecipient recipient, String contactName,
                                         String senderFullName, Company company) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("recipient.name", nz(recipient.name()));
        tokens.put("recipient.address", nz(recipient.address()));
        tokens.put("recipient.email", nz(recipient.email()));
        tokens.put("recipient.phone", nz(recipient.phone()));
        tokens.put("recipient.country", nz(recipient.country()));
        // The named person the email is addressed to, falling back to the partner's own name when the
        // send goes to the company address. Never empty: this token's whole purpose is to open a
        // greeting, and a bulk send with nobody named would otherwise put "Dear ," in front of every
        // customer - which is worse than being slightly impersonal.
        tokens.put("recipient.contactName",
                contactName != null && !contactName.isBlank() ? contactName : nz(recipient.name()));
        // Legacy aliases - see the note above.
        tokens.put("manufacturer.name", nz(recipient.name()));
        tokens.put("manufacturer.address", nz(recipient.address()));
        tokens.put("manufacturer.email", nz(recipient.email()));
        tokens.put("manufacturer.phone", nz(recipient.phone()));
        tokens.put("manufacturer.country", nz(recipient.country()));
        tokens.put("sender.fullName", nz(senderFullName));
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
