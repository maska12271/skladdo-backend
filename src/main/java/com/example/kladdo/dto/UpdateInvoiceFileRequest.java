package com.example.kladdo.dto;

/**
 * Attaches (or replaces) the supplier invoice document on a purchase order. The file itself is uploaded
 * separately via {@code /api/upload/document}; this carries the resulting url and original filename.
 */
public record UpdateInvoiceFileRequest(
        String invoiceFileUrl,
        String invoiceFileName
) {
}
