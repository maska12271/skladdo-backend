package com.example.kladdo.service;

import com.example.kladdo.dto.CompanySettingsDto;
import com.example.kladdo.dto.CreateInvoiceRequest;
import com.example.kladdo.dto.InvoiceDetailsDto;
import com.example.kladdo.dto.InvoiceSummaryDto;
import com.example.kladdo.dto.OrderPaymentSummaryDto;
import com.example.kladdo.dto.UpdateInvoicePaymentRequest;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.*;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.InvoiceRepository;
import com.example.kladdo.repository.SalesOrderRepository;
import com.example.kladdo.security.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Invoicing flow: generating a PDF invoice for a sales order, tracking payment, and deriving overdue
 * status and late-payment penalty. Money fields and payment terms are snapshotted at generation time so
 * an issued invoice never changes; overdue/penalty are computed live by {@link PenaltyCalculator} while
 * an invoice is unpaid and frozen into {@link Invoice#getPenaltyAmountCharged()} when it is marked paid.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final CompanyRepository companyRepository;
    private final CompanySettingsService settingsService;
    private final InvoicePdfService pdfService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          SalesOrderRepository salesOrderRepository,
                          CompanyRepository companyRepository,
                          CompanySettingsService settingsService,
                          InvoicePdfService pdfService) {
        this.invoiceRepository = invoiceRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.companyRepository = companyRepository;
        this.settingsService = settingsService;
        this.pdfService = pdfService;
    }

    // ---------------------------------------------------------------------------------------------
    // Generation
    // ---------------------------------------------------------------------------------------------

    /**
     * Generates an invoice record for the given sales order from the choices captured in the create
     * dialog ({@link CreateInvoiceRequest} - a null/empty request reproduces the old default-everything
     * behaviour). Rejects cancelled orders and a second active invoice of the same {@link InvoiceType}
     * (correct by voiding and regenerating). Money fields, payment terms and buyer details are
     * snapshotted here; the PDF is rendered on demand from this data (see {@link #getPdf(Long)}).
     */
    @Transactional
    public InvoiceDetailsDto generateForSalesOrder(Long salesOrderId, CreateInvoiceRequest request) {
        CreateInvoiceRequest req = request != null ? request : CreateInvoiceRequest.empty();
        SalesOrder order = salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sales order not found with id: " + salesOrderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BadRequestException("error.invoice.cancelledOrder");
        }

        InvoiceType type = req.type() != null ? req.type() : InvoiceType.FINAL;

        // Active (non-void) invoices already on the order; effective type coalesces legacy nulls to FINAL.
        List<Invoice> active = invoiceRepository.findBySalesOrderIdAndStatusNot(salesOrderId, InvoicePaymentStatus.VOID);
        if (active.stream().anyMatch(i -> i.getType() == type)) {
            throw new BadRequestException(type == InvoiceType.PREPAYMENT
                    ? "error.invoice.activePrepaymentExists"
                    : "error.invoice.activeInvoiceExists");
        }

        CompanySettings settings = settingsService.getOrCreate();

        Invoice invoice = new Invoice();
        invoice.setSalesOrder(order);
        invoice.setStatus(InvoicePaymentStatus.UNPAID);
        invoice.setType(type);
        invoice.setCurrency(settings.getCurrency());

        // Payment terms: request override -> order override -> company settings.
        int termDays = firstNonNull(req.paymentTermDays(), order.getPaymentTermDays(),
                settings.getInvoicePaymentTermDays(), 0);
        invoice.setPenaltyPercent(firstNonNull(req.penaltyPercent(), order.getPenaltyPercent(),
                nz(settings.getLatePaymentPenaltyPercent()), BigDecimal.ZERO));
        invoice.setPenaltyPeriod(firstNonNull(req.penaltyPeriod(), order.getPenaltyPeriod(),
                settings.getPenaltyPeriod(), PenaltyPeriod.DAILY));

        LocalDate issueDate = req.issueDate() != null ? req.issueDate() : LocalDate.now();
        LocalDate dueDate = req.dueDate() != null ? req.dueDate() : issueDate.plusDays(termDays);
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);
        invoice.setNotes(req.notes() != null ? req.notes() : order.getNotes());

        // Buyer snapshot (keep the link too, for an optional "view client" navigation).
        Client client = order.getClient();
        if (client != null) {
            invoice.setClient(client);
            invoice.setClientName(client.getName());
            invoice.setClientAddress(client.getAddress());
            invoice.setClientRegistrationCode(client.getRegistrationCode());
            invoice.setClientEmail(client.getEmail());
        }

        if (type == InvoiceType.PREPAYMENT) {
            buildPrepayment(invoice, order, req, settings);
        } else {
            buildFinal(invoice, order, active);
        }

        invoice.setInvoiceNumber(settingsService.allocateNextInvoiceNumber());
        return toDetails(invoiceRepository.save(invoice), LocalDate.now());
    }

    /**
     * Snapshots the full order onto a final invoice (gross total, all lines), and nets off a paid
     * prepayment against the balance due when one exists. Blocked while a prepayment is still unpaid -
     * that must be settled or voided first so the final invoice's balance is unambiguous.
     */
    private void buildFinal(Invoice invoice, SalesOrder order, List<Invoice> active) {
        Invoice unpaidPrepayment = active.stream()
                .filter(i -> i.getType() == InvoiceType.PREPAYMENT && i.getStatus() == InvoicePaymentStatus.UNPAID)
                .findFirst().orElse(null);
        if (unpaidPrepayment != null) {
            throw new BadRequestException("error.invoice.prepaymentUnsettled",
                    unpaidPrepayment.getInvoiceNumber());
        }

        // Money snapshot. The order's totalAmount is net (subtotal + delivery); the invoice total due is
        // gross, so add the tax on top.
        BigDecimal subtotal = nz(order.getSubtotalAmount());
        BigDecimal tax = nz(order.getTaxAmount());
        BigDecimal delivery = nz(order.getDeliveryPrice());
        BigDecimal gross = subtotal.add(tax).add(delivery);
        invoice.setSubtotalAmount(subtotal);
        invoice.setTaxAmount(tax);
        invoice.setDeliveryPrice(delivery);
        invoice.setTotalAmount(gross);

        // Line snapshot (discount/tax come from the order line, frozen at generation time).
        for (SalesOrderItem item : order.getItems()) {
            Product product = item.getProduct();
            InvoiceItem line = new InvoiceItem();
            line.setInvoice(invoice);
            line.setProduct(product);
            line.setProductName(product != null ? product.getName() : null);
            line.setSku(product != null ? product.getSku() : null);
            line.setQuantity(item.getQuantity());
            line.setUnitPrice(item.getUnitPrice());
            line.setDiscountPercent(item.getDiscountPercent());
            line.setTaxRatePercent(item.getTaxRatePercent());
            line.setLineTotal(item.getLineTotal());
            invoice.getItems().add(line);
        }

        Invoice paidPrepayment = active.stream()
                .filter(i -> i.getType() == InvoiceType.PREPAYMENT && i.getStatus() == InvoicePaymentStatus.PAID)
                .findFirst().orElse(null);
        if (paidPrepayment != null) {
            invoice.setAppliedPrepaymentInvoice(paidPrepayment);
            invoice.setAppliedPrepaymentAmount(nz(paidPrepayment.getTotalAmount()).min(gross));
        }
    }

    /**
     * Builds a deposit invoice for a share of the order. The deposit is taken as a fraction of the
     * order's gross total (an explicit amount wins over a percentage, which itself falls back to the
     * company default), and the order's subtotal / tax / delivery are split proportionally so the
     * prepayment document stays VAT-consistent. A single synthetic line describes the deposit.
     */
    private void buildPrepayment(Invoice invoice, SalesOrder order, CreateInvoiceRequest req, CompanySettings settings) {
        BigDecimal subtotal = nz(order.getSubtotalAmount());
        BigDecimal tax = nz(order.getTaxAmount());
        BigDecimal delivery = nz(order.getDeliveryPrice());
        BigDecimal gross = subtotal.add(tax).add(delivery);
        if (gross.signum() <= 0) {
            throw new BadRequestException("error.invoice.prepaymentNoValue");
        }

        BigDecimal fraction;
        if (req.prepaymentAmount() != null && req.prepaymentAmount().signum() > 0) {
            fraction = req.prepaymentAmount().divide(gross, 6, RoundingMode.HALF_UP);
        } else {
            BigDecimal percent = req.prepaymentPercent() != null && req.prepaymentPercent().signum() > 0
                    ? req.prepaymentPercent()
                    : nz(settings.getDefaultPrepaymentPercent());
            fraction = percent.movePointLeft(2);
        }
        if (fraction.signum() <= 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new BadRequestException("error.invoice.prepaymentRange");
        }

        BigDecimal preSubtotal = subtotal.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal preTax = tax.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal preDelivery = delivery.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        invoice.setSubtotalAmount(preSubtotal);
        invoice.setTaxAmount(preTax);
        invoice.setDeliveryPrice(preDelivery);
        invoice.setTotalAmount(preSubtotal.add(preTax).add(preDelivery));
        invoice.setPrepaymentPercent(fraction.movePointRight(2).setScale(3, RoundingMode.HALF_UP));

        // Blended tax rate for the synthetic line's tax column (order tax over order subtotal).
        BigDecimal blendedTaxPercent = subtotal.signum() > 0
                ? tax.divide(subtotal, 4, RoundingMode.HALF_UP).movePointRight(2).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        String orderRef = order.getOrderNumber() != null ? order.getOrderNumber() : ("#" + order.getId());
        InvoiceItem line = new InvoiceItem();
        line.setInvoice(invoice);
        line.setProductName("Prepayment (" + invoice.getPrepaymentPercent().stripTrailingZeros().toPlainString()
                + "%) — Order " + orderRef);
        line.setQuantity(1);
        line.setUnitPrice(preSubtotal);
        line.setDiscountPercent(BigDecimal.ZERO);
        line.setTaxRatePercent(blendedTaxPercent);
        line.setLineTotal(preSubtotal);
        invoice.getItems().add(line);
    }

    // ---------------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<InvoiceSummaryDto> findAll(InvoicePaymentStatus status, boolean overdueOnly, Long clientId,
                                           LocalDate dateFrom, LocalDate dateTo, Pageable pageable) {
        LocalDate today = LocalDate.now();

        Specification<Invoice> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (overdueOnly) {
                // Overdue is a derived condition, expressed directly as a predicate (no stored flag).
                predicates.add(cb.equal(root.get("status"), InvoicePaymentStatus.UNPAID));
                predicates.add(cb.lessThan(root.get("dueDate"), today));
            }
            if (clientId != null) {
                predicates.add(cb.equal(root.get("client").get("id"), clientId));
            }
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("issueDate"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("issueDate"), dateTo));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return invoiceRepository.findAll(specification, pageable).map(inv -> toSummary(inv, today));
    }

    @Transactional(readOnly = true)
    public InvoiceDetailsDto getDetails(Long id) {
        return toDetails(require(id), LocalDate.now());
    }

    /** All invoices for a sales order (newest first); used by the order detail page's invoice card. */
    @Transactional(readOnly = true)
    public List<InvoiceDetailsDto> findBySalesOrder(Long salesOrderId) {
        LocalDate today = LocalDate.now();
        return invoiceRepository.findBySalesOrderIdOrderByIdDesc(salesOrderId).stream()
                .map(inv -> toDetails(inv, today))
                .toList();
    }

    /** Renders the invoice PDF on demand from its snapshotted data (tenant-scoped via the lookup). */
    @Transactional(readOnly = true)
    public byte[] getPdf(Long id) {
        Invoice invoice = require(id);
        CompanySettings settings = settingsService.getOrCreate();
        Company company = companyRepository.findById(currentCompanyId())
                .orElseThrow(() -> new IllegalStateException("Current company not found"));
        return pdfService.render(invoice, company, settings);
    }

    /** The invoice number, for naming the downloaded file. */
    @Transactional(readOnly = true)
    public String getInvoiceNumber(Long id) {
        return require(id).getInvoiceNumber();
    }

    /**
     * Renders a sample invoice PDF for the given (unsaved) settings so the settings page can preview a
     * layout choice. The settings are applied to a transient entity and never persisted; only the current
     * company is read from the database.
     */
    @Transactional(readOnly = true)
    public byte[] renderPreview(CompanySettingsDto settingsDto) {
        CompanySettings settings = new CompanySettings();
        settingsDto.applyTo(settings);
        Company company = companyRepository.findById(currentCompanyId())
                .orElseThrow(() -> new IllegalStateException("Current company not found"));
        return pdfService.renderSample(company, settings);
    }

    // ---------------------------------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------------------------------

    @Transactional
    public InvoiceDetailsDto updatePaymentStatus(Long id, UpdateInvoicePaymentRequest request) {
        Invoice invoice = require(id);
        if (invoice.getStatus() == InvoicePaymentStatus.VOID) {
            throw new BadRequestException("error.invoice.voidedNoStatusChange");
        }
        if (request.status() == InvoicePaymentStatus.VOID) {
            throw new BadRequestException("error.invoice.useVoidEndpoint");
        }

        if (request.status() == InvoicePaymentStatus.PAID) {
            LocalDate paidDate = request.paidDate() != null ? request.paidDate() : LocalDate.now();
            invoice.setPaidDate(paidDate);
            invoice.setStatus(InvoicePaymentStatus.PAID);
            // Freeze the penalty owed as of the payment date so the figure stops climbing. Charged on the
            // outstanding principal (total less any applied prepayment), not the gross total.
            invoice.setPenaltyAmountCharged(PenaltyCalculator.calculate(
                    principal(invoice), invoice.getPenaltyPercent(), invoice.getPenaltyPeriod(),
                    invoice.getDueDate(), paidDate).penaltyAmount());
        } else {
            // Undo a mistaken "paid" click.
            invoice.setStatus(InvoicePaymentStatus.UNPAID);
            invoice.setPaidDate(null);
            invoice.setPenaltyAmountCharged(null);
        }

        return toDetails(invoiceRepository.save(invoice), LocalDate.now());
    }

    @Transactional
    public InvoiceDetailsDto voidInvoice(Long id) {
        Invoice invoice = require(id);
        // A prepayment already netted into a live final invoice can't be voided out from under it.
        if (invoice.getType() == InvoiceType.PREPAYMENT
                && invoiceRepository.existsByAppliedPrepaymentInvoiceIdAndStatusNot(id, InvoicePaymentStatus.VOID)) {
            throw new BadRequestException("error.invoice.prepaymentAppliedToFinal");
        }
        invoice.setStatus(InvoicePaymentStatus.VOID);
        invoice.setPaidDate(null);
        invoice.setPenaltyAmountCharged(null);
        return toDetails(invoiceRepository.save(invoice), LocalDate.now());
    }

    /**
     * A one-row-per-order billing summary for the sales-orders list: each order's derived
     * {@link OrderPaymentStatus} plus the money still owed on its active invoices. Only orders that carry
     * at least one invoice appear; the caller treats any order not in the result as {@code NOT_INVOICED}.
     */
    @Transactional(readOnly = true)
    public List<OrderPaymentSummaryDto> paymentSummaries() {
        LocalDate today = LocalDate.now();
        Map<Long, List<Invoice>> byOrder = new HashMap<>();
        for (Invoice inv : invoiceRepository.findAll()) {
            Long orderId = inv.getSalesOrder() != null ? inv.getSalesOrder().getId() : null;
            if (orderId == null) continue;
            byOrder.computeIfAbsent(orderId, k -> new ArrayList<>()).add(inv);
        }
        List<OrderPaymentSummaryDto> summaries = new ArrayList<>();
        for (Map.Entry<Long, List<Invoice>> entry : byOrder.entrySet()) {
            summaries.add(summarise(entry.getKey(), entry.getValue(), today));
        }
        return summaries;
    }

    /** Derives one order's {@link OrderPaymentStatus} and amount due from its invoices (see enum precedence). */
    private OrderPaymentSummaryDto summarise(Long orderId, List<Invoice> invoices, LocalDate today) {
        Invoice fin = invoices.stream()
                .filter(i -> i.getStatus() != InvoicePaymentStatus.VOID && i.getType() == InvoiceType.FINAL)
                .findFirst().orElse(null);
        Invoice pre = invoices.stream()
                .filter(i -> i.getStatus() != InvoicePaymentStatus.VOID && i.getType() == InvoiceType.PREPAYMENT)
                .findFirst().orElse(null);

        Invoice governing = fin != null ? fin : pre;
        if (governing == null) {
            return new OrderPaymentSummaryDto(orderId, OrderPaymentStatus.NOT_INVOICED.name(), false, null,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        OrderPaymentStatus status;
        boolean overdue = false;
        BigDecimal amountDue = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;

        if (governing.getStatus() == InvoicePaymentStatus.PAID) {
            status = fin != null ? OrderPaymentStatus.PAID : OrderPaymentStatus.AWAITING_FINAL;
        } else {
            PenaltyCalculator.Result result = penaltyFor(governing, today);
            overdue = result.overdue();
            penalty = result.penaltyAmount();
            amountDue = amountDue(governing, result);
            if (fin != null) {
                status = overdue ? OrderPaymentStatus.OVERDUE : OrderPaymentStatus.INVOICED;
            } else {
                status = overdue ? OrderPaymentStatus.PREPAYMENT_OVERDUE : OrderPaymentStatus.PREPAYMENT_PENDING;
            }
        }
        return new OrderPaymentSummaryDto(orderId, status.name(), overdue, governing.getCurrency(), amountDue, penalty);
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private Invoice require(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));
    }

    private Long currentCompanyId() {
        Long companyId = TenantContext.getCompanyId();
        if (companyId == null) {
            throw new IllegalStateException("No company bound to the current request");
        }
        return companyId;
    }

    /**
     * Penalty/overdue figures for an invoice as of {@code today}: computed live while unpaid, taken from
     * the frozen charge once paid, and zero when voided.
     */
    private PenaltyCalculator.Result penaltyFor(Invoice invoice, LocalDate today) {
        if (invoice.getStatus() == InvoicePaymentStatus.UNPAID) {
            return PenaltyCalculator.calculate(principal(invoice), invoice.getPenaltyPercent(),
                    invoice.getPenaltyPeriod(), invoice.getDueDate(), today);
        }
        BigDecimal frozen = invoice.getPenaltyAmountCharged() != null
                ? invoice.getPenaltyAmountCharged() : BigDecimal.ZERO;
        return new PenaltyCalculator.Result(false, 0, frozen);
    }

    /** Amount still owed: principal + accrued penalty while unpaid, otherwise nothing. */
    private BigDecimal amountDue(Invoice invoice, PenaltyCalculator.Result penalty) {
        if (invoice.getStatus() == InvoicePaymentStatus.UNPAID) {
            return principal(invoice).add(penalty.penaltyAmount());
        }
        return BigDecimal.ZERO;
    }

    /** Outstanding principal the penalty accrues on: the total less any prepayment already applied. */
    private BigDecimal principal(Invoice invoice) {
        return nz(invoice.getTotalAmount()).subtract(nz(invoice.getAppliedPrepaymentAmount()));
    }

    private InvoiceSummaryDto toSummary(Invoice invoice, LocalDate today) {
        PenaltyCalculator.Result penalty = penaltyFor(invoice, today);
        return new InvoiceSummaryDto(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getStatus() != null ? invoice.getStatus().name() : null,
                invoice.getType().name(),
                invoice.getSalesOrder() != null ? invoice.getSalesOrder().getId() : null,
                invoice.getSalesOrder() != null ? invoice.getSalesOrder().getOrderNumber() : null,
                invoice.getClient() != null ? invoice.getClient().getId() : null,
                invoice.getClientName(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.getPaidDate(),
                invoice.getCurrency(),
                nz(invoice.getTotalAmount()),
                penalty.overdue(),
                penalty.penaltyAmount(),
                amountDue(invoice, penalty)
        );
    }

    private InvoiceDetailsDto toDetails(Invoice invoice, LocalDate today) {
        PenaltyCalculator.Result penalty = penaltyFor(invoice, today);
        List<InvoiceDetailsDto.Line> lines = new ArrayList<>();
        for (InvoiceItem item : invoice.getItems()) {
            lines.add(new InvoiceDetailsDto.Line(
                    item.getProduct() != null ? item.getProduct().getId() : null,
                    item.getProductName(),
                    item.getSku(),
                    item.getQuantity() != null ? item.getQuantity() : 0,
                    nz(item.getUnitPrice()),
                    item.getDiscountPercent(),
                    item.getTaxRatePercent(),
                    nz(item.getLineTotal())
            ));
        }
        return new InvoiceDetailsDto(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getStatus() != null ? invoice.getStatus().name() : null,
                invoice.getType().name(),
                invoice.getSalesOrder() != null ? invoice.getSalesOrder().getId() : null,
                invoice.getSalesOrder() != null ? invoice.getSalesOrder().getOrderNumber() : null,
                invoice.getClient() != null ? invoice.getClient().getId() : null,
                invoice.getClientName(),
                invoice.getClientAddress(),
                invoice.getClientRegistrationCode(),
                invoice.getClientEmail(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.getPaidDate(),
                invoice.getCurrency(),
                nz(invoice.getSubtotalAmount()),
                nz(invoice.getTaxAmount()),
                nz(invoice.getDeliveryPrice()),
                nz(invoice.getTotalAmount()),
                nz(invoice.getPenaltyPercent()),
                invoice.getPenaltyPeriod() != null ? invoice.getPenaltyPeriod().name() : null,
                penalty.overdue(),
                penalty.penaltyAmount(),
                amountDue(invoice, penalty),
                invoice.getPrepaymentPercent(),
                invoice.getAppliedPrepaymentAmount(),
                invoice.getAppliedPrepaymentInvoice() != null
                        ? invoice.getAppliedPrepaymentInvoice().getInvoiceNumber() : null,
                invoice.getNotes(),
                lines,
                invoice.getCreatedAt(),
                invoice.getUpdatedAt()
        );
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // First non-null of the request override, the order override and the company default, else the
    // hard fallback. One overload per type used in term resolution.

    private static int firstNonNull(Integer a, Integer b, Integer c, int fallback) {
        if (a != null) return a;
        if (b != null) return b;
        if (c != null) return c;
        return fallback;
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b, BigDecimal c, BigDecimal fallback) {
        if (a != null) return a;
        if (b != null) return b;
        if (c != null) return c;
        return fallback;
    }

    private static PenaltyPeriod firstNonNull(PenaltyPeriod a, PenaltyPeriod b, PenaltyPeriod c, PenaltyPeriod fallback) {
        if (a != null) return a;
        if (b != null) return b;
        if (c != null) return c;
        return fallback;
    }
}
