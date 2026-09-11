package com.example.skladdo.dto;

/**
 * One thing missing from an invoice that would weaken - or invalidate - the e-invoice XML built from it.
 *
 * <p>{@code code} is a stable identifier the frontend turns into a sentence, so the wording (and the
 * "fix it here" pointer that goes with it) lives with the rest of the UI text rather than in the API.</p>
 */
public record EInvoiceIssueDto(String code, Severity severity) {

    /**
     * {@link #BLOCKING} means the document would be incomplete in a way receivers reject - a mandatory
     * element would go out empty. {@link #WARNING} means it is valid but degraded: something optional in
     * the schema that recipients commonly insist on, such as a VAT number or a postal address.
     */
    public enum Severity {
        BLOCKING,
        WARNING
    }

    public static EInvoiceIssueDto blocking(String code) {
        return new EInvoiceIssueDto(code, Severity.BLOCKING);
    }

    public static EInvoiceIssueDto warning(String code) {
        return new EInvoiceIssueDto(code, Severity.WARNING);
    }
}
