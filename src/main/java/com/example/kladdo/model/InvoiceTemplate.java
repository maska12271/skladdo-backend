package com.example.kladdo.model;

/**
 * Visual layout used when rendering a sales invoice to PDF. Stored on {@link CompanySettings} so a
 * company can pick the look that matches its branding; {@code com.example.kladdo.service.InvoicePdfService}
 * maps each value to a Thymeleaf template under {@code templates/invoice/}.
 */
public enum InvoiceTemplate {
    /** The original layout: right-aligned "INVOICE" title with accent-coloured headings and ruled tables. */
    CLASSIC,
    /** A full-width coloured banner header with the logo and invoice meta inside the accent band. */
    MODERN,
    /** A restrained, monochrome layout with hairline rules and no filled backgrounds. */
    MINIMAL
}
