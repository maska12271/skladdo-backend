package com.example.skladdo.service;

import com.example.skladdo.dto.CompanySettingsDto;
import com.example.skladdo.dto.CreateCreditNoteRequest;
import com.example.skladdo.dto.CreateInvoiceRequest;
import com.example.skladdo.dto.EInvoiceIssueDto;
import com.example.skladdo.dto.InvoiceDetailsDto;
import com.example.skladdo.dto.InvoiceSummaryDto;
import com.example.skladdo.dto.OrderPaymentSummaryDto;
import com.example.skladdo.dto.UpdateInvoicePaymentRequest;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.*;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.InvoiceRepository;
import com.example.skladdo.repository.SalesOrderRepository;
import com.example.skladdo.security.TenantContext;
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
    private final EInvoiceXmlService eInvoiceXmlService;
    private final EInvoiceReadinessService eInvoiceReadinessService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          SalesOrderRepository salesOrderRepository,
                          CompanyRepository companyRepository,
                          CompanySettingsService settingsService,
                          InvoicePdfService pdfService,
                          EInvoiceXmlService eInvoiceXmlService,
                          EInvoiceReadinessService eInvoiceReadinessService) {
        this.eInvoiceReadinessService = eInvoiceReadinessService;
        this.invoiceRepository = invoiceRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.companyRepository = companyRepository;
        this.settingsService = settingsService;
        this.pdfService = pdfService;
        this.eInvoiceXmlService = eInvoiceXmlService;
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

        // Invoices still standing on the order; effective type coalesces legacy nulls to FINAL.
        List<Invoice> active = liveInvoices(salesOrderId);
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
        // The order's currency, not the company's. Every amount below is copied from the order without
        // conversion, so stamping the base currency here labelled a foreign figure with the wrong unit -
        // a 1000 USD order printed as "1000.00 EUR" on the customer's PDF (finding F-015). The rest of the
        // system already treats these amounts as the order's currency: DashboardService converts them to
        // base by dividing by the *order's* exchange rate, which is only correct if that is what they are.
        invoice.setCurrency(order.getCurrency() != null && !order.getCurrency().isBlank()
                ? order.getCurrency()
                : settings.getCurrency());

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
            // Both shapes: the composed line is what the PDF prints, the parts are what the e-invoice needs.
            invoice.setClientAddress(client.getAddress());
            invoice.setClientAddressStreet(client.getAddressStreet());
            invoice.setClientAddressCity(client.getAddressCity());
            invoice.setClientAddressPostalCode(client.getAddressPostalCode());
            invoice.setClientCountry(client.getCountry());
            invoice.setClientRegistrationCode(client.getRegistrationCode());
            invoice.setClientVatNumber(client.getVatNumber());
            invoice.setClientDepartmentId(client.getInvoiceDepartmentId());
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
     * Snapshots the full order onto a final invoice (gross total, all lines), and nets off any active
     * prepayment against the balance due.
     *
     * <p>The deduction does not care whether the prepayment has been paid yet. What it is netting off is
     * an amount that has already been <em>invoiced</em>, and that is true from the moment the prepayment
     * document exists - so the two documents always sum to the order total, and the customer is never
     * asked for more than the order is worth. Waiting for payment used to block the final invoice
     * outright, which forced the deposit to be settled or voided before ordinary invoicing could
     * continue, for a document that was already correct either way.</p>
     */
    private void buildFinal(Invoice invoice, SalesOrder order, List<Invoice> active) {
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
            com.example.skladdo.model.Service service = item.getService();
            InvoiceItem line = new InvoiceItem();
            line.setInvoice(invoice);
            line.setProduct(product);
            // productName/sku are frozen display text, not a product reference (see InvoiceItem), so a
            // service line puts its own name/code through the same two fields rather than needing a
            // parallel pair the PDF templates would all have to learn.
            line.setProductName(product != null ? product.getName()
                    : service != null ? service.getName() : null);
            line.setSku(product != null ? product.getSku()
                    : service != null ? service.getCode() : null);
            line.setQuantity(item.getQuantity());
            line.setUnitPrice(item.getUnitPrice());
            line.setDiscountPercent(item.getDiscountPercent());
            line.setTaxRatePercent(item.getTaxRatePercent());
            line.setLineTotal(item.getLineTotal());
            invoice.getItems().add(line);
        }

        // Any non-void prepayment on the order, paid or not - `active` already excludes VOID ones, and a
        // voided deposit is the one case where nothing should be deducted.
        Invoice prepayment = active.stream()
                .filter(i -> i.getType() == InvoiceType.PREPAYMENT)
                .findFirst().orElse(null);
        if (prepayment != null) {
            invoice.setAppliedPrepaymentInvoice(prepayment);
            // Capped at the order's gross so an over-sized deposit can never produce a negative balance.
            invoice.setAppliedPrepaymentAmount(nz(prepayment.getTotalAmount()).min(gross));
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

    /**
     * Renders the invoice PDF on demand from its snapshotted data (tenant-scoped via the lookup). Not
     * read-only: on a company's first PDF, {@code getOrCreate()} inserts the default settings row, which
     * Postgres rejects inside a read-only transaction.
     */
    @Transactional
    public byte[] getPdf(Long id) {
        Invoice invoice = require(id);
        CompanySettings settings = settingsService.getOrCreate();
        Company company = companyRepository.findById(currentCompanyId())
                .orElseThrow(() -> new IllegalStateException("Current company not found"));
        return pdfService.render(invoice, company, settings);
    }

    /**
     * Builds the Estonian e-invoice XML for this invoice, from the same snapshotted data the PDF is
     * rendered from. Not read-only for the same reason as {@link #getPdf(Long)}.
     *
     * <p>A voided invoice is refused: the XML is a document the buyer's accounting system will import and
     * book as payable, and a cancelled invoice must not be able to enter that pipeline at all.</p>
     */
    @Transactional
    public byte[] getEInvoiceXml(Long id) {
        Invoice invoice = require(id);
        if (invoice.getStatus() == InvoicePaymentStatus.VOID) {
            throw new BadRequestException("error.invoice.voidedNoEInvoice");
        }
        CompanySettings settings = settingsService.getOrCreate();
        Company company = companyRepository.findById(currentCompanyId())
                .orElseThrow(() -> new IllegalStateException("Current company not found"));
        return eInvoiceXmlService.render(invoice, company, settings);
    }

    /**
     * What this invoice is missing for a clean e-invoice export. Empty means it exports cleanly; the
     * caller decides whether to go ahead anyway, since an incomplete file is still often useful.
     */
    @Transactional
    public List<EInvoiceIssueDto> getEInvoiceReadiness(Long id) {
        Invoice invoice = require(id);
        CompanySettings settings = settingsService.getOrCreate();
        Company company = companyRepository.findById(currentCompanyId())
                .orElseThrow(() -> new IllegalStateException("Current company not found"));
        return eInvoiceReadinessService.check(invoice, company, settings);
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

    /**
     * Issues a credit note (kreeditarve) reversing an already-sent invoice, and moves that invoice to
     * {@link InvoicePaymentStatus#CREDITED}.
     *
     * <p>This is the correction path for an invoice the customer has already received. Voiding is the
     * other one, and the difference is what the books end up saying: a voided invoice never counted,
     * while a credited one was genuinely issued and is reversed by a second document that stays on
     * record. Both free the order to be invoiced again.</p>
     *
     * <p>The credit note mirrors the original's amounts and lines as positive figures with nothing due -
     * Estonian law does not recognise a negative invoice total, so the reversal is carried by the
     * document's type rather than by its sign.</p>
     */
    @Transactional
    public InvoiceDetailsDto createCreditNote(Long invoiceId, CreateCreditNoteRequest request) {
        Invoice original = require(invoiceId);

        if (original.getType() == InvoiceType.CREDIT) {
            throw new BadRequestException("error.invoice.creditNoteNotCreditable");
        }
        if (original.getStatus() == InvoicePaymentStatus.VOID) {
            throw new BadRequestException("error.invoice.voidedNoCreditNote");
        }
        BigDecimal remaining = nz(original.getTotalAmount()).subtract(nz(original.getCreditedAmount()));
        if (remaining.signum() <= 0) {
            throw new BadRequestException("error.invoice.alreadyCredited");
        }
        // Same rule as voiding: a deposit already netted into a live final invoice cannot be pulled out
        // from under it, or that invoice's balance stops adding up.
        if (original.getType() == InvoiceType.PREPAYMENT
                && invoiceRepository.existsByAppliedPrepaymentInvoiceIdAndStatusNot(invoiceId, InvoicePaymentStatus.VOID)) {
            throw new BadRequestException("error.invoice.prepaymentAppliedToFinal");
        }

        LocalDate issueDate = request.issueDate() != null ? request.issueDate() : LocalDate.now();

        Invoice credit = new Invoice();
        credit.setSalesOrder(original.getSalesOrder());
        credit.setType(InvoiceType.CREDIT);
        credit.setCreditedInvoice(original);
        credit.setIssueDate(issueDate);
        // Nothing is ever owed on a credit note, so it is settled the moment it is issued. Recording that
        // as PAID is what keeps every roll-up correct without special-casing: they all read "not UNPAID"
        // as "nothing more to collect". No due date, for the same reason.
        credit.setStatus(InvoicePaymentStatus.PAID);
        credit.setPaidDate(issueDate);
        credit.setCurrency(original.getCurrency());

        // Amounts and lines: a full credit mirrors the original exactly, a partial one is computed from
        // what was actually selected. See buildFullCredit / buildPartialCredit for why they differ.
        List<CreditedLine> credited = resolveCreditedLines(original, request);
        boolean creditDelivery = request.creditDelivery() == null || request.creditDelivery();
        if (isWholeInvoice(original, credited, creditDelivery)) {
            buildFullCredit(credit, original);
        } else {
            buildPartialCredit(credit, original, credited, creditDelivery);
        }
        if (nz(credit.getTotalAmount()).signum() <= 0) {
            throw new BadRequestException("error.invoice.creditNoteNoValue");
        }
        if (nz(credit.getTotalAmount()).compareTo(remaining) > 0) {
            throw new BadRequestException("error.invoice.creditNoteExceedsRemaining");
        }

        // No late-payment terms apply to a document with no due date; the columns are NOT NULL.
        credit.setPenaltyPercent(BigDecimal.ZERO);
        credit.setPenaltyPeriod(original.getPenaltyPeriod() != null ? original.getPenaltyPeriod() : PenaltyPeriod.DAILY);

        // Buyer snapshot is copied rather than re-read: the credit note must name the buyer exactly as the
        // invoice it reverses did, even if the client record has changed since.
        credit.setClient(original.getClient());
        credit.setClientName(original.getClientName());
        credit.setClientAddress(original.getClientAddress());
        credit.setClientAddressStreet(original.getClientAddressStreet());
        credit.setClientAddressCity(original.getClientAddressCity());
        credit.setClientAddressPostalCode(original.getClientAddressPostalCode());
        credit.setClientCountry(original.getClientCountry());
        credit.setClientRegistrationCode(original.getClientRegistrationCode());
        credit.setClientVatNumber(original.getClientVatNumber());
        credit.setClientDepartmentId(original.getClientDepartmentId());
        credit.setClientEmail(original.getClientEmail());

        credit.setNotes(request.reason());
        credit.setInvoiceNumber(settingsService.allocateNextInvoiceNumber());

        BigDecimal creditedSoFar = nz(original.getCreditedAmount()).add(nz(credit.getTotalAmount()));
        original.setCreditedAmount(creditedSoFar);
        // Only a fully reversed invoice is CREDITED. A partial credit leaves the rest genuinely owed, so
        // the invoice stays where it was and simply owes less.
        if (creditedSoFar.compareTo(nz(original.getTotalAmount())) >= 0) {
            original.setStatus(InvoicePaymentStatus.CREDITED);
            // Nothing is owed any more, so a penalty frozen against it goes too.
            original.setPenaltyAmountCharged(null);
        }
        invoiceRepository.save(original);

        return toDetails(invoiceRepository.save(credit), LocalDate.now());
    }

    /**
     * Copies the invoice wholesale. The original's own totals are reused rather than recomputed so a full
     * credit reverses it to the cent - the order's tax was rounded per line, so adding the lines back up
     * can land a penny away from what the invoice actually said.
     */
    private void buildFullCredit(Invoice credit, Invoice original) {
        credit.setSubtotalAmount(nz(original.getSubtotalAmount()));
        credit.setTaxAmount(nz(original.getTaxAmount()));
        credit.setDeliveryPrice(nz(original.getDeliveryPrice()));
        credit.setTotalAmount(nz(original.getTotalAmount()));
        for (InvoiceItem item : original.getItems()) {
            credit.getItems().add(copyLine(credit, item, item.getQuantity(), nz(item.getLineTotal())));
        }
    }

    /**
     * Builds a credit note for part of the invoice. Each selected line is credited pro rata - crediting
     * two of five items reverses two fifths of that line's net, discount included - and the tax is then
     * recomputed per line from the credited net, since there is no original figure to copy for a portion.
     */
    private void buildPartialCredit(Invoice credit, Invoice original, List<CreditedLine> credited,
                                    boolean creditDelivery) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;

        for (CreditedLine selection : credited) {
            InvoiceItem item = selection.item();
            int originalQty = item.getQuantity() != null ? item.getQuantity() : 0;
            BigDecimal net = originalQty > 0
                    ? nz(item.getLineTotal())
                        .multiply(BigDecimal.valueOf(selection.quantity()))
                        .divide(BigDecimal.valueOf(originalQty), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            subtotal = subtotal.add(net);
            tax = tax.add(net.multiply(nz(item.getTaxRatePercent())).movePointLeft(2)
                    .setScale(2, RoundingMode.HALF_UP));
            credit.getItems().add(copyLine(credit, item, selection.quantity(), net));
        }

        BigDecimal delivery = creditDelivery ? nz(original.getDeliveryPrice()) : BigDecimal.ZERO;
        credit.setSubtotalAmount(subtotal);
        credit.setTaxAmount(tax);
        credit.setDeliveryPrice(delivery);
        credit.setTotalAmount(subtotal.add(tax).add(delivery));
    }

    private InvoiceItem copyLine(Invoice credit, InvoiceItem source, Integer quantity, BigDecimal lineTotal) {
        InvoiceItem line = new InvoiceItem();
        line.setInvoice(credit);
        line.setProduct(source.getProduct());
        line.setProductName(source.getProductName());
        line.setSku(source.getSku());
        line.setQuantity(quantity);
        line.setUnitPrice(source.getUnitPrice());
        line.setDiscountPercent(source.getDiscountPercent());
        line.setTaxRatePercent(source.getTaxRatePercent());
        line.setLineTotal(lineTotal);
        return line;
    }

    /**
     * Pairs each requested line with the invoice item it credits, rejecting anything that does not belong
     * to this invoice or asks for more than was sold. An empty request means the whole invoice.
     */
    private List<CreditedLine> resolveCreditedLines(Invoice original, CreateCreditNoteRequest request) {
        if (!request.isPartial()) {
            return original.getItems().stream()
                    .map(item -> new CreditedLine(item, item.getQuantity() != null ? item.getQuantity() : 0))
                    .toList();
        }
        Map<Long, InvoiceItem> byId = new HashMap<>();
        for (InvoiceItem item : original.getItems()) {
            byId.put(item.getId(), item);
        }
        List<CreditedLine> resolved = new ArrayList<>();
        for (CreateCreditNoteRequest.CreditLine line : request.lines()) {
            InvoiceItem item = byId.get(line.invoiceItemId());
            if (item == null) {
                throw new BadRequestException("error.invoice.creditNoteUnknownLine");
            }
            int available = item.getQuantity() != null ? item.getQuantity() : 0;
            if (line.quantity() > available) {
                throw new BadRequestException("error.invoice.creditNoteQuantityTooHigh");
            }
            resolved.add(new CreditedLine(item, line.quantity()));
        }
        return resolved;
    }

    /**
     * Whether the selection actually amounts to the entire invoice - every line at its full quantity, and
     * the delivery charge with it. Decided here rather than trusted from the client, so a request that
     * happens to name everything still gets the exact-mirror treatment.
     */
    private boolean isWholeInvoice(Invoice original, List<CreditedLine> credited, boolean creditDelivery) {
        if (!creditDelivery && nz(original.getDeliveryPrice()).signum() > 0) {
            return false;
        }
        if (credited.size() != original.getItems().size()) {
            return false;
        }
        return credited.stream().allMatch(c ->
                c.quantity() == (c.item().getQuantity() != null ? c.item().getQuantity() : 0));
    }

    /** One of the original's lines together with how much of it is being credited. */
    private record CreditedLine(InvoiceItem item, int quantity) {
    }

    @Transactional
    public InvoiceDetailsDto voidInvoice(Long id) {
        Invoice invoice = require(id);
        // A prepayment already netted into a live final invoice can't be voided out from under it.
        if (invoice.getType() == InvoiceType.PREPAYMENT
                && invoiceRepository.existsByAppliedPrepaymentInvoiceIdAndStatusNot(id, InvoicePaymentStatus.VOID)) {
            throw new BadRequestException("error.invoice.prepaymentAppliedToFinal");
        }
        // Voiding a credit note gives back what it reversed, which would otherwise stay written off by a
        // document that no longer counts - owed by nobody and settled by nothing.
        Invoice credited = invoice.getCreditedInvoice();
        if (invoice.getType() == InvoiceType.CREDIT && credited != null
                && invoice.getStatus() != InvoicePaymentStatus.VOID) {
            BigDecimal remaining = nz(credited.getCreditedAmount()).subtract(nz(invoice.getTotalAmount()));
            credited.setCreditedAmount(remaining.max(BigDecimal.ZERO));
            if (credited.getStatus() == InvoicePaymentStatus.CREDITED) {
                credited.setStatus(InvoicePaymentStatus.UNPAID);
            }
            invoiceRepository.save(credited);
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
        // Reversed invoices and the credit notes that reversed them are both out: nothing is owed on
        // either, and an order whose only invoice has been credited is back to needing one.
        Invoice fin = invoices.stream()
                .filter(InvoiceService::isLive).filter(i -> i.getType() == InvoiceType.FINAL)
                .findFirst().orElse(null);
        Invoice pre = invoices.stream()
                .filter(InvoiceService::isLive).filter(i -> i.getType() == InvoiceType.PREPAYMENT)
                .findFirst().orElse(null);

        Invoice governing = fin != null ? fin : pre;
        if (governing == null) {
            return new OrderPaymentSummaryDto(orderId, OrderPaymentStatus.NOT_INVOICED.name(), false, null,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // Both documents can be owed at once. A final invoice nets the deposit off its own balance whether
        // or not the deposit has been paid, so an unpaid deposit is still outstanding alongside it - summing
        // them is what keeps the order's figure equal to what the customer actually owes. With only one
        // invoice on the order this collapses to exactly what it did before.
        List<Invoice> owed = new ArrayList<>();
        owed.add(governing);
        if (fin != null && pre != null) {
            owed.add(pre);
        }

        boolean overdue = false;
        BigDecimal amountDue = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;
        for (Invoice inv : owed) {
            PenaltyCalculator.Result result = penaltyFor(inv, today);
            amountDue = amountDue.add(amountDue(inv, result));
            penalty = penalty.add(result.penaltyAmount());
            // Guarded on UNPAID: a settled invoice's due date is history and must not colour the order.
            overdue = overdue || (inv.getStatus() == InvoicePaymentStatus.UNPAID && result.overdue());
        }

        // Settled means every live invoice is paid, not just the governing one - a paid final on top of an
        // unpaid deposit used to report the whole order as paid.
        boolean settled = owed.stream().allMatch(i -> i.getStatus() == InvoicePaymentStatus.PAID);

        OrderPaymentStatus status;
        if (settled) {
            status = fin != null ? OrderPaymentStatus.PAID : OrderPaymentStatus.AWAITING_FINAL;
        } else if (fin != null) {
            status = overdue ? OrderPaymentStatus.OVERDUE : OrderPaymentStatus.INVOICED;
        } else {
            status = overdue ? OrderPaymentStatus.PREPAYMENT_OVERDUE : OrderPaymentStatus.PREPAYMENT_PENDING;
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

    /**
     * The invoices on an order that still stand: neither voided nor reversed by a credit note, and not
     * credit notes themselves.
     *
     * <p>This is what "does the order already have one of these?" and "is there a deposit to net off?"
     * both mean. Crediting therefore frees the order to be invoiced again exactly as voiding does - the
     * difference between them is what the books keep, not what the order can do next.</p>
     */
    private List<Invoice> liveInvoices(Long salesOrderId) {
        return invoiceRepository.findBySalesOrderIdAndStatusNot(salesOrderId, InvoicePaymentStatus.VOID).stream()
                .filter(InvoiceService::isLive)
                .toList();
    }

    /** See {@link #liveInvoices}: a standing, payable-in-principle document rather than a reversal. */
    private static boolean isLive(Invoice invoice) {
        return invoice.getStatus() != InvoicePaymentStatus.VOID
                && invoice.getStatus() != InvoicePaymentStatus.CREDITED
                && invoice.getType() != InvoiceType.CREDIT;
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

    /**
     * Outstanding principal the penalty accrues on: the total less any prepayment already applied and
     * anything reversed by a credit note. Never negative - crediting an invoice reduces what is owed to
     * zero at the very most, it does not turn it into a debt owed back.
     */
    private BigDecimal principal(Invoice invoice) {
        BigDecimal owed = nz(invoice.getTotalAmount())
                .subtract(nz(invoice.getAppliedPrepaymentAmount()))
                .subtract(nz(invoice.getCreditedAmount()));
        return owed.max(BigDecimal.ZERO);
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
                    item.getId(),
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
                invoice.getCreditedInvoice() != null
                        ? invoice.getCreditedInvoice().getInvoiceNumber() : null,
                nz(invoice.getCreditedAmount()),
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
