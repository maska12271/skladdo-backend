package com.example.skladdo.service;

import com.example.skladdo.dto.CreateCreditNoteRequest;
import com.example.skladdo.dto.InvoiceDetailsDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.Invoice;
import com.example.skladdo.model.InvoiceItem;
import com.example.skladdo.model.InvoicePaymentStatus;
import com.example.skladdo.model.InvoiceType;
import com.example.skladdo.model.PenaltyPeriod;
import com.example.skladdo.model.SalesOrder;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.InvoiceRepository;
import com.example.skladdo.repository.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a credit note reverses, in money. The arithmetic is the risky half of the feature: a full credit
 * must undo the invoice to the cent, a partial one must reverse only the share selected, and neither may
 * leave the invoice owing a different amount than it should.
 *
 * <p>Only the repository and settings collaborators are stubbed; the amount logic under test is real.</p>
 */
class CreditNoteAmountsTest {

    private InvoiceRepository invoiceRepository;
    private InvoiceService service;
    private Invoice saved;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        CompanySettingsService settingsService = mock(CompanySettingsService.class);
        when(settingsService.allocateNextInvoiceNumber()).thenReturn("INV-000200");
        // Capture whatever is written, and hand entities back unchanged so the returned DTO is real.
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(call -> {
            Invoice argument = call.getArgument(0);
            if (argument.getType() == InvoiceType.CREDIT) {
                saved = argument;
            }
            return argument;
        });
        when(invoiceRepository.existsByAppliedPrepaymentInvoiceIdAndStatusNot(anyLong(), any()))
                .thenReturn(false);

        EInvoiceXmlService xmlService = new EInvoiceXmlService();
        service = new InvoiceService(invoiceRepository, mock(SalesOrderRepository.class),
                mock(CompanyRepository.class), settingsService, mock(InvoicePdfService.class),
                xmlService, new EInvoiceReadinessService(xmlService));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void fullCreditMirrorsTheInvoiceToTheCent() {
        Invoice original = invoice();
        // Deliberately a penny away from the sum of the lines, as per-line tax rounding leaves it.
        original.setTaxAmount(new BigDecimal("240.78"));
        original.setTotalAmount(new BigDecimal("1361.98"));

        InvoiceDetailsDto credit = service.createCreditNote(1L, request(null, true));

        // Copied, not recomputed - otherwise a full credit would fail to undo its own invoice exactly.
        assertThat(credit.totalAmount()).isEqualByComparingTo("1361.98");
        assertThat(credit.taxAmount()).isEqualByComparingTo("240.78");
        assertThat(saved.getItems()).hasSize(3);
        assertThat(original.getStatus()).isEqualTo(InvoicePaymentStatus.CREDITED);
        assertThat(original.getCreditedAmount()).isEqualByComparingTo("1361.98");
    }

    @Test
    void partialCreditReversesOnlyTheSelectedShareOfALine() {
        Invoice original = invoice();

        // Two of the four chairs: half of that line's 638.00 net, plus 22% tax on it.
        InvoiceDetailsDto credit = service.createCreditNote(1L,
                request(List.of(new CreateCreditNoteRequest.CreditLine(2L, 2)), false));

        assertThat(credit.subtotalAmount()).isEqualByComparingTo("319.00");
        assertThat(credit.taxAmount()).isEqualByComparingTo("70.18");
        assertThat(credit.deliveryPrice()).isEqualByComparingTo("0.00");
        assertThat(credit.totalAmount()).isEqualByComparingTo("389.18");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().getFirst().getQuantity()).isEqualTo(2);
    }

    @Test
    void aPartiallyCreditedInvoiceStaysOwedForTheRest() {
        Invoice original = invoice();

        service.createCreditNote(1L, request(List.of(new CreateCreditNoteRequest.CreditLine(2L, 2)), false));

        // The point of partial credits: the invoice is not settled, it simply owes less.
        assertThat(original.getStatus()).isEqualTo(InvoicePaymentStatus.UNPAID);
        assertThat(original.getCreditedAmount()).isEqualByComparingTo("389.18");
    }

    @Test
    void creditsAccumulateUntilTheInvoiceIsFullyReversed() {
        Invoice original = invoice();
        original.setTotalAmount(new BigDecimal("389.18"));
        original.setSubtotalAmount(new BigDecimal("319.00"));

        service.createCreditNote(1L, request(List.of(new CreateCreditNoteRequest.CreditLine(2L, 2)), false));

        assertThat(original.getCreditedAmount()).isEqualByComparingTo("389.18");
        assertThat(original.getStatus()).isEqualTo(InvoicePaymentStatus.CREDITED);
    }

    @Test
    void deliveryIsCreditedOnlyWhenAskedFor() {
        invoice();
        InvoiceDetailsDto withDelivery = service.createCreditNote(1L,
                request(List.of(new CreateCreditNoteRequest.CreditLine(3L, 1)), true));
        assertThat(withDelivery.deliveryPrice()).isEqualByComparingTo("15.00");
        // 20.00 net + 9% tax + 15.00 delivery
        assertThat(withDelivery.totalAmount()).isEqualByComparingTo("36.80");
    }

    @Test
    void namingEveryLineInFullIsTreatedAsAWholeInvoiceCredit() {
        Invoice original = invoice();
        original.setTaxAmount(new BigDecimal("240.78"));
        original.setTotalAmount(new BigDecimal("1361.98"));

        // The client always sends the lines explicitly; the server decides this is really a full credit,
        // so the exact figures are mirrored rather than recomputed a penny off.
        InvoiceDetailsDto credit = service.createCreditNote(1L, request(List.of(
                new CreateCreditNoteRequest.CreditLine(1L, 2),
                new CreateCreditNoteRequest.CreditLine(2L, 4),
                new CreateCreditNoteRequest.CreditLine(3L, 1)), true));

        assertThat(credit.totalAmount()).isEqualByComparingTo("1361.98");
        assertThat(credit.taxAmount()).isEqualByComparingTo("240.78");
    }

    // --- Guards -----------------------------------------------------------------------------------

    @Test
    void cannotCreditMoreOfALineThanWasInvoiced() {
        invoice();
        assertThatThrownBy(() -> service.createCreditNote(1L,
                request(List.of(new CreateCreditNoteRequest.CreditLine(2L, 5)), false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.invoice.creditNoteQuantityTooHigh");
    }

    @Test
    void cannotCreditALineFromAnotherInvoice() {
        invoice();
        assertThatThrownBy(() -> service.createCreditNote(1L,
                request(List.of(new CreateCreditNoteRequest.CreditLine(999L, 1)), false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.invoice.creditNoteUnknownLine");
    }

    @Test
    void cannotIssueACreditNoteThatReversesNothing() {
        invoice();
        assertThatThrownBy(() -> service.createCreditNote(1L, request(List.of(), false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.invoice.creditNoteNoValue");
    }

    @Test
    void cannotCreditAnInvoiceThatIsAlreadyFullyCredited() {
        Invoice original = invoice();
        original.setCreditedAmount(original.getTotalAmount());
        assertThatThrownBy(() -> service.createCreditNote(1L, request(null, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.invoice.alreadyCredited");
    }

    @Test
    void cannotCreditBeyondWhatIsLeft() {
        Invoice original = invoice();
        // Only 100.00 left to reverse, but the selection is worth far more.
        original.setCreditedAmount(original.getTotalAmount().subtract(new BigDecimal("100.00")));
        assertThatThrownBy(() -> service.createCreditNote(1L,
                request(List.of(new CreateCreditNoteRequest.CreditLine(2L, 4)), false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.invoice.creditNoteExceedsRemaining");
    }

    @Test
    void aCreditNoteCannotItselfBeCredited() {
        Invoice creditNote = invoice();
        creditNote.setType(InvoiceType.CREDIT);
        assertThatThrownBy(() -> service.createCreditNote(1L, request(null, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.invoice.creditNoteNotCreditable");
    }

    @Test
    void aVoidedInvoiceCannotBeCredited() {
        Invoice original = invoice();
        original.setStatus(InvoicePaymentStatus.VOID);
        assertThatThrownBy(() -> service.createCreditNote(1L, request(null, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.invoice.voidedNoCreditNote");
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    private static CreateCreditNoteRequest request(List<CreateCreditNoteRequest.CreditLine> lines, boolean delivery) {
        return new CreateCreditNoteRequest(LocalDate.of(2026, 3, 20), "Goods returned", lines, delivery);
    }

    /** Registers a three-line invoice as the one the repository will return for id 1. */
    private Invoice invoice() {
        Invoice invoice = new Invoice();
        invoice.setId(1L);
        invoice.setInvoiceNumber("INV-000123");
        invoice.setStatus(InvoicePaymentStatus.UNPAID);
        invoice.setType(InvoiceType.FINAL);
        invoice.setIssueDate(LocalDate.of(2026, 3, 1));
        invoice.setCurrency("EUR");
        invoice.setPenaltyPeriod(PenaltyPeriod.DAILY);

        SalesOrder order = new SalesOrder();
        order.setOrderNumber("SO-000045");
        invoice.setSalesOrder(order);
        invoice.setClientName("City Hospital");

        AtomicLong lineId = new AtomicLong(1);
        line(invoice, lineId.getAndIncrement(), "Oak office desk", 2, "249.00", "22", "448.20");
        line(invoice, lineId.getAndIncrement(), "Ergonomic chair", 4, "159.50", "22", "638.00");
        line(invoice, lineId.getAndIncrement(), "Safety handbook", 1, "20.00", "9", "20.00");

        invoice.setSubtotalAmount(new BigDecimal("1106.20"));
        invoice.setTaxAmount(new BigDecimal("240.76"));
        invoice.setDeliveryPrice(new BigDecimal("15.00"));
        invoice.setTotalAmount(new BigDecimal("1361.96"));

        when(invoiceRepository.findById(1L)).thenReturn(java.util.Optional.of(invoice));
        return invoice;
    }

    private static void line(Invoice invoice, long id, String name, int qty, String unitPrice,
                             String taxPercent, String lineTotal) {
        InvoiceItem item = new InvoiceItem();
        item.setId(id);
        item.setInvoice(invoice);
        item.setProductName(name);
        item.setQuantity(qty);
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setDiscountPercent(BigDecimal.ZERO);
        item.setTaxRatePercent(new BigDecimal(taxPercent));
        item.setLineTotal(new BigDecimal(lineTotal));
        invoice.getItems().add(item);
    }
}
