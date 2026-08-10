package com.example.kladdo.repository;

import com.example.kladdo.model.Invoice;
import com.example.kladdo.model.InvoicePaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    /** Invoices issued for a given sales order, newest first (company-scoped via the tenant filter). */
    List<Invoice> findBySalesOrderIdOrderByIdDesc(Long salesOrderId);

    /**
     * Active invoices for a sales order (those not in the given status - always called with
     * {@link InvoicePaymentStatus#VOID}). Effective type is decided in Java so legacy rows with a null
     * type read as {@link com.example.kladdo.model.InvoiceType#FINAL}.
     */
    List<Invoice> findBySalesOrderIdAndStatusNot(Long salesOrderId, InvoicePaymentStatus status);

    /** Whether a non-voided invoice already applies the given prepayment (blocks voiding that prepayment). */
    boolean existsByAppliedPrepaymentInvoiceIdAndStatusNot(Long appliedPrepaymentInvoiceId, InvoicePaymentStatus status);

    /** Unpaid invoices whose due date has passed - drives the overdue notification job. */
    List<Invoice> findByStatusAndDueDateBefore(InvoicePaymentStatus status, java.time.LocalDate date);
}
