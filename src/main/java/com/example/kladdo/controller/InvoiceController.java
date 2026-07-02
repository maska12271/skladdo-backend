package com.example.kladdo.controller;

import com.example.kladdo.dto.InvoiceDetailsDto;
import com.example.kladdo.dto.InvoiceSummaryDto;
import com.example.kladdo.dto.UpdateInvoicePaymentRequest;
import com.example.kladdo.model.InvoicePaymentStatus;
import com.example.kladdo.service.InvoiceService;
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

    @PatchMapping("/{id}/payment")
    @PreAuthorize("@perm.canEdit(authentication, 'INVOICES')")
    public InvoiceDetailsDto updatePayment(@PathVariable Long id, @Valid @RequestBody UpdateInvoicePaymentRequest request) {
        return invoiceService.updatePaymentStatus(id, request);
    }

    @PatchMapping("/{id}/void")
    @PreAuthorize("@perm.canEdit(authentication, 'INVOICES')")
    public InvoiceDetailsDto voidInvoice(@PathVariable Long id) {
        return invoiceService.voidInvoice(id);
    }
}
