package com.example.kladdo.controller;

import com.example.kladdo.dto.CreatePurchaseOrderRequest;
import com.example.kladdo.dto.OrderDetailsDto;
import com.example.kladdo.dto.UpdateFulfilmentRequest;
import com.example.kladdo.dto.UpdateInvoiceFileRequest;
import com.example.kladdo.dto.UpdatePurchaseOrderRequest;
import com.example.kladdo.dto.UpdateStatusRequest;
import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.model.PurchaseOrder;
import com.example.kladdo.model.PurchaseOrder;
import com.example.kladdo.service.CompanySettingsService;
import com.example.kladdo.service.OrderAnalyticsService;
import com.example.kladdo.service.PurchaseOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
@Tag(name = "Purchase Orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final OrderAnalyticsService orderAnalyticsService;
    private final CompanySettingsService settingsService;
    private final Path uploadDir;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService,
                                   OrderAnalyticsService orderAnalyticsService,
                                   CompanySettingsService settingsService,
                                   @Value("${app.upload.dir:./uploads}") String uploadDirPath) {
        this.purchaseOrderService = purchaseOrderService;
        this.orderAnalyticsService = orderAnalyticsService;
        this.settingsService = settingsService;
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
    }

    /** System-suggested next order number for the create form (does not advance the counter). */
    @GetMapping("/next-number")
    @PreAuthorize("@perm.canCreate(authentication, 'PURCHASE_ORDERS')")
    public java.util.Map<String, String> nextNumber() {
        return java.util.Map.of("number", settingsService.peekNextPurchaseOrderNumber());
    }

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'PURCHASE_ORDERS')")
    public Page<PurchaseOrder> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<Long> manufacturerId,
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return purchaseOrderService.findAll(search, manufacturerId, status, warehouseId, dateFrom, dateTo, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canView(authentication, 'PURCHASE_ORDERS')")
    public PurchaseOrder getById(@PathVariable Long id) {
        return purchaseOrderService.findById(id);
    }

    @GetMapping("/{id}/details")
    @PreAuthorize("@perm.canView(authentication, 'PURCHASE_ORDERS')")
    public OrderDetailsDto getDetails(@PathVariable Long id) {
        return orderAnalyticsService.getPurchaseOrderDetails(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'PURCHASE_ORDERS')")
    public PurchaseOrder create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return purchaseOrderService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'PURCHASE_ORDERS')")
    public PurchaseOrder update(@PathVariable Long id, @Valid @RequestBody UpdatePurchaseOrderRequest request) {
        return purchaseOrderService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("@perm.canEdit(authentication, 'PURCHASE_ORDERS')")
    public PurchaseOrder updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        return purchaseOrderService.updateStatus(id, request.status());
    }

    /**
     * Records goods-receipt progress against the order's lines and returns the refreshed details.
     *
     * <p>Open to warehouse staff who can post inventory movements as well as to users who can edit the
     * order: checking a delivery in is their job, and it changes no order data beyond the received counts
     * (in particular it never moves stock - that still happens on the status change).</p>
     */
    @PutMapping("/{id}/receipt")
    @PreAuthorize("@perm.canEdit(authentication, 'PURCHASE_ORDERS') or @perm.canCreate(authentication, 'INVENTORY')")
    public OrderDetailsDto updateReceipt(@PathVariable Long id,
                                         @Valid @RequestBody UpdateFulfilmentRequest request) {
        purchaseOrderService.updateReceipt(id, request);
        return orderAnalyticsService.getPurchaseOrderDetails(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'PURCHASE_ORDERS')")
    public void delete(@PathVariable Long id) {
        purchaseOrderService.delete(id);
    }

    /** Attaches (or replaces) the supplier invoice document on an order — used from the detail page. */
    @PutMapping("/{id}/invoice-file")
    @PreAuthorize("@perm.canEdit(authentication, 'PURCHASE_ORDERS')")
    public PurchaseOrder setInvoiceFile(@PathVariable Long id, @RequestBody UpdateInvoiceFileRequest request) {
        return purchaseOrderService.setInvoiceFile(id, request.invoiceFileUrl(), request.invoiceFileName());
    }

    /** Removes the attached supplier invoice document from an order. */
    @DeleteMapping("/{id}/invoice-file")
    @PreAuthorize("@perm.canEdit(authentication, 'PURCHASE_ORDERS')")
    public PurchaseOrder clearInvoiceFile(@PathVariable Long id) {
        return purchaseOrderService.setInvoiceFile(id, null, null);
    }

    /**
     * Streams the order's attached supplier invoice as a download with its original filename. Served
     * through this authenticated endpoint (rather than the public {@code /uploads} path) so the document
     * is only reachable by users who can view purchase orders. 404 when no file is attached.
     */
    @GetMapping("/{id}/invoice-file")
    @PreAuthorize("@perm.canView(authentication, 'PURCHASE_ORDERS')")
    public ResponseEntity<Resource> downloadInvoiceFile(@PathVariable Long id) throws IOException {
        PurchaseOrder order = purchaseOrderService.findById(id);
        String url = order.getInvoiceFileUrl();
        if (url == null || url.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        // Resolve only the stored filename inside the upload dir (guards against path traversal).
        String storedName = Paths.get(url).getFileName().toString();
        Path file = uploadDir.resolve(storedName).normalize();
        if (!file.startsWith(uploadDir) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        String downloadName = order.getInvoiceFileName() != null && !order.getInvoiceFileName().isBlank()
                ? order.getInvoiceFileName() : storedName;
        String contentType = Files.probeContentType(file);
        return ResponseEntity.ok()
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloadName.replace("\"", "") + "\"")
                .body(new UrlResource(file.toUri()));
    }
}