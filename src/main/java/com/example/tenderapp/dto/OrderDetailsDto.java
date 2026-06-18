package com.example.tenderapp.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the order detail page renders for a single sales or purchase order: header fields,
 * line items (with an estimated unit cost for sales lines), money/quantity totals, the audit trail
 * (who created/last edited it) and the full status-change timeline.
 */
public record OrderDetailsDto(
        Long id,
        String type,                 // "SALES" | "PURCHASE"
        String orderNumber,
        String status,
        Long counterpartyId,         // client (sales) or manufacturer (purchase)
        String counterpartyName,
        String counterpartyLabel,    // "Client" | "Manufacturer"
        LocalDate orderDate,
        LocalDate closingDate,
        LocalDate expectedDeliveryDate, // null for sales orders
        String deliveryAddress,
        String notes,
        List<Line> items,
        Totals totals,
        AuditInfo audit,
        List<StatusEvent> statusHistory
) {

    public record Actor(Long id, String name) {
    }

    public record AuditInfo(
            Actor createdBy,
            Instant createdAt,
            Actor updatedBy,
            Instant updatedAt
    ) {
    }

    public record Line(
            Long productId,
            String productName,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal estUnitCost   // avg purchase cost per unit (sales lines only); null otherwise
    ) {
    }

    public record Totals(
            BigDecimal subtotal,
            BigDecimal deliveryPrice,
            BigDecimal total,
            long totalUnits,
            int productCount,
            BigDecimal deliveryPerUnit,
            BigDecimal estCost,      // estimated cost of goods (sales only); null for purchases
            BigDecimal estProfit     // subtotal - estCost (sales only); null for purchases
    ) {
    }

    public record StatusEvent(
            String fromStatus,       // null for the initial "created" event
            String toStatus,
            Instant changedAt,
            Actor changedBy
    ) {
    }
}
