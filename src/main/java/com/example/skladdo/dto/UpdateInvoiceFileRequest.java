package com.example.skladdo.dto;

/**
 * Attaches (or replaces) the supplier invoice document on a purchase order. The file itself is uploaded
 * separately via {@code /api/upload/document}; this carries the resulting S3 key and original filename.
 */
public record UpdateInvoiceFileRequest(
        String invoiceFileKey,
        String invoiceFileName
) {
}
