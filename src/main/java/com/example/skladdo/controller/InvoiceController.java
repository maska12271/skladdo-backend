package com.example.skladdo.controller;

import com.example.skladdo.dto.CreateCreditNoteRequest;
import com.example.skladdo.dto.EInvoiceIssueDto;
import com.example.skladdo.dto.InvoiceDetailsDto;
import com.example.skladdo.dto.InvoiceSummaryDto;
import com.example.skladdo.dto.UpdateInvoicePaymentRequest;
import com.example.skladdo.model.InvoicePaymentStatus;
import com.example.skladdo.service.InvoiceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/invoices")
@Tag(name = "Invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'INVOICES')")
    public Page<InvoiceSummaryDto> getAll(
            @RequestParam(required = false) InvoicePaymentStatus status,
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return invoiceService.findAll(status, overdueOnly, clientId, dateFrom, dateTo, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canView(authentication, 'INVOICES')")
    public InvoiceDetailsDto getById(@PathVariable Long id) {
        return invoiceService.getDetails(id);
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("@perm.canView(authentication, 'INVOICES')")
    public ResponseEntity<ByteArrayResource> getPdf(@PathVariable Long id) {
        byte[] pdf = invoiceService.getPdf(id);
        String filename = invoiceService.getInvoiceNumber(id) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(new ByteArrayResource(pdf));
    }

    /**
     * The invoice as an Estonian e-invoice ("e-arve") XML file, for import into the buyer's accounting
     * system or upload to an e-invoice operator. Downloaded as an attachment - unlike the PDF there is
     * nothing useful to show inline.
     */
    @GetMapping("/{id}/e-invoice")
    @PreAuthorize("@perm.canView(authentication, 'INVOICES')")
    public ResponseEntity<ByteArrayResource> getEInvoice(@PathVariable Long id) {
        byte[] xml = invoiceService.getEInvoiceXml(id);
        String filename = "e-arve-" + invoiceService.getInvoiceNumber(id) + ".xml";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new ByteArrayResource(xml));
    }

    /**
     * What the invoice is missing for a clean e-invoice export, so the user sees it before downloading
     * rather than after the buyer rejects the file. An empty list means it exports cleanly.
     */
    @GetMapping("/{id}/e-invoice/readiness")
    @PreAuthorize("@perm.canView(authentication, 'INVOICES')")
    public List<EInvoiceIssueDto> getEInvoiceReadiness(@PathVariable Long id) {
        return invoiceService.getEInvoiceReadiness(id);
    }

    @PatchMapping("/{id}/payment")
    @PreAuthorize("@perm.canEdit(authentication, 'INVOICES')")
    public InvoiceDetailsDto updatePayment(@PathVariable Long id, @Valid @RequestBody UpdateInvoicePaymentRequest request) {
        return invoiceService.updatePaymentStatus(id, request);
    }

    /**
     * Issues a credit note reversing this invoice. The correction path for an invoice the customer has
     * already received - voiding is for one that should never have counted.
     */
    @PostMapping("/{id}/credit-note")
    @PreAuthorize("@perm.canCreate(authentication, 'INVOICES')")
    public InvoiceDetailsDto createCreditNote(@PathVariable Long id,
                                              @Valid @RequestBody CreateCreditNoteRequest request) {
        return invoiceService.createCreditNote(id, request);
    }

    @PatchMapping("/{id}/void")
    @PreAuthorize("@perm.canEdit(authentication, 'INVOICES')")
    public InvoiceDetailsDto voidInvoice(@PathVariable Long id) {
        return invoiceService.voidInvoice(id);
    }
}
