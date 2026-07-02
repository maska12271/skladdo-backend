package com.example.kladdo.dto;

import com.example.kladdo.model.InvoicePaymentStatus;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Marks an invoice paid or unpaid. {@code status} must be {@link InvoicePaymentStatus#PAID} or
 * {@link InvoicePaymentStatus#UNPAID} (voiding has its own endpoint). {@code paidDate} is optional and
 * only used when marking paid - it defaults to today and is the date the penalty is frozen against.
 */
public record UpdateInvoicePaymentRequest(
        @NotNull InvoicePaymentStatus status,
        LocalDate paidDate
) {
}
