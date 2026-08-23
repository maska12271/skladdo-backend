package com.example.skladdo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Compact {@link com.example.skladdo.model.Invoice} row for the invoices list. Like
 * {@link InvoiceDetailsDto}, the {@code overdue}/{@code penaltyAmount}/{@code amountDue} fields are
 * computed server-side so the table can render them directly.
 */
public record InvoiceSummaryDto(
        Long id,
        String invoiceNumber,
        String status,
        String type,

        Long salesOrderId,
        String salesOrderNumber,

        Long clientId,
        String clientName,

        LocalDate issueDate,
        LocalDate dueDate,
        LocalDate paidDate,

        String currency,
        BigDecimal totalAmount,

        boolean overdue,
        BigDecimal penaltyAmount,
        BigDecimal amountDue
) {
}
