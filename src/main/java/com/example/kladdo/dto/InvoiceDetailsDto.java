package com.example.kladdo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Full view of an {@link com.example.kladdo.model.Invoice} for the detail panel and the order page's
 * invoice card. {@code overdue}, {@code penaltyAmount} and {@code amountDue} are computed by the service
 * (live from the due date while unpaid, from the frozen charge once paid) so the client never has to
 * re-derive them.
 */
public record InvoiceDetailsDto(
        Long id,
        String invoiceNumber,
        String status,
        String type,

        Long salesOrderId,
        String salesOrderNumber,

        Long clientId,
        String clientName,
        String clientAddress,
        String clientRegistrationCode,
        String clientEmail,

        LocalDate issueDate,
        LocalDate dueDate,
        LocalDate paidDate,

        String currency,
        BigDecimal subtotalAmount,
        BigDecimal taxAmount,
        BigDecimal deliveryPrice,
        BigDecimal totalAmount,

        BigDecimal penaltyPercent,
        String penaltyPeriod,

        boolean overdue,
        BigDecimal penaltyAmount,
        BigDecimal amountDue,

        // Prepayment: for a PREPAYMENT invoice, its share of the order; for a FINAL invoice, the paid
        // prepayment deducted from the balance due (null when none applies).
        BigDecimal prepaymentPercent,
        BigDecimal appliedPrepaymentAmount,
        String appliedPrepaymentNumber,

        String notes,
        List<Line> items,

        Instant createdAt,
        Instant updatedAt
) {
    public record Line(
            Long productId,
            String productName,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal discountPercent,
            BigDecimal taxRatePercent,
            BigDecimal lineTotal
    ) {
    }
}
