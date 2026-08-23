package com.example.skladdo.dto;

import com.example.skladdo.model.InvoiceType;
import com.example.skladdo.model.PenaltyPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Choices captured in the "Create invoice" dialog before an invoice is generated for a sales order.
 * Every field is optional: an empty request reproduces the old one-click behaviour (a final invoice
 * issued today, on the order's / company's default terms). The service fills each gap from the sales
 * order's own overrides, then the company settings.
 *
 * <ul>
 *   <li>{@code type} - {@link InvoiceType#FINAL} (default) or {@link InvoiceType#PREPAYMENT}.</li>
 *   <li>{@code issueDate} - defaults to today; {@code dueDate} defaults to {@code issueDate + termDays}.</li>
 *   <li>{@code paymentTermDays} - used to derive {@code dueDate} when the latter is not given explicitly.</li>
 *   <li>{@code penaltyPercent} / {@code penaltyPeriod} - the late-payment terms snapshotted onto the invoice.</li>
 *   <li>{@code prepaymentPercent} / {@code prepaymentAmount} - for a prepayment invoice, the deposit as a
 *       percentage of the order total, or as an explicit amount (the amount wins if both are supplied).</li>
 *   <li>{@code notes} - defaults to the order's notes.</li>
 * </ul>
 */
public record CreateInvoiceRequest(
        InvoiceType type,
        LocalDate issueDate,
        Integer paymentTermDays,
        LocalDate dueDate,
        BigDecimal penaltyPercent,
        PenaltyPeriod penaltyPeriod,
        BigDecimal prepaymentPercent,
        BigDecimal prepaymentAmount,
        String notes
) {
    /** An empty request - reproduces the previous default-everything generation. */
    public static CreateInvoiceRequest empty() {
        return new CreateInvoiceRequest(null, null, null, null, null, null, null, null, null);
    }
}
