package com.example.kladdo.controller;

import com.example.kladdo.dto.CreateInvoiceRequest;
import com.example.kladdo.dto.CreateSalesOrderRequest;
import com.example.kladdo.dto.InvoiceDetailsDto;
import com.example.kladdo.dto.OrderDetailsDto;
import com.example.kladdo.dto.OrderPaymentSummaryDto;
import com.example.kladdo.dto.UpdateFulfilmentRequest;
import com.example.kladdo.dto.UpdateSalesOrderRequest;
import com.example.kladdo.dto.UpdateStatusRequest;
import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.model.SalesOrder;
import com.example.kladdo.service.CompanySettingsService;
import com.example.kladdo.service.InvoiceService;
import com.example.kladdo.service.OrderAnalyticsService;
import com.example.kladdo.service.SalesOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales-orders")
@Tag(name = "Sales Orders")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;
    private final OrderAnalyticsService orderAnalyticsService;
    private final InvoiceService invoiceService;
    private final CompanySettingsService settingsService;

    public SalesOrderController(SalesOrderService salesOrderService,
                                OrderAnalyticsService orderAnalyticsService,
                                InvoiceService invoiceService,
                                CompanySettingsService settingsService) {
        this.salesOrderService = salesOrderService;
        this.orderAnalyticsService = orderAnalyticsService;
        this.invoiceService = invoiceService;
        this.settingsService = settingsService;
    }

    /** System-suggested next order number for the create form (does not advance the counter). */
    @GetMapping("/next-number")
    @PreAuthorize("@perm.canCreate(authentication, 'SALES_ORDERS')")
    public Map<String, String> nextNumber() {
        return Map.of("number", settingsService.peekNextSalesOrderNumber());
    }

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'SALES_ORDERS')")
    public Page<SalesOrder> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<Long> clientId,
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) List<Long> id,
            @RequestParam(required = false) List<Long> excludeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return salesOrderService.findAll(search, clientId, status, warehouseId, dateFrom, dateTo, id, excludeId, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canView(authentication, 'SALES_ORDERS')")
    public SalesOrder getById(@PathVariable Long id) {
        return salesOrderService.findById(id);
    }

    @GetMapping("/{id}/details")
    @PreAuthorize("@perm.canView(authentication, 'SALES_ORDERS')")
    public OrderDetailsDto getDetails(@PathVariable Long id) {
        return orderAnalyticsService.getSalesOrderDetails(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'SALES_ORDERS')")
    public SalesOrder create(@Valid @RequestBody CreateSalesOrderRequest request) {
        return salesOrderService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'SALES_ORDERS')")
    public SalesOrder update(@PathVariable Long id, @Valid @RequestBody UpdateSalesOrderRequest request) {
        return salesOrderService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("@perm.canEdit(authentication, 'SALES_ORDERS')")
    public SalesOrder updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        return salesOrderService.updateStatus(id, request.status());
    }

    /**
     * Records picking progress against the order's lines and returns the refreshed details.
     *
     * <p>Open to warehouse staff who can post inventory movements as well as to users who can edit the
     * order: picking is their job, and it changes no order data beyond the picked counts (in particular
     * it never moves stock - that still happens on the status change).</p>
     */
    @PutMapping("/{id}/fulfilment")
    @PreAuthorize("@perm.canEdit(authentication, 'SALES_ORDERS') or @perm.canCreate(authentication, 'INVENTORY')")
    public OrderDetailsDto updateFulfilment(@PathVariable Long id,
                                            @Valid @RequestBody UpdateFulfilmentRequest request) {
        salesOrderService.updateFulfilment(id, request);
        return orderAnalyticsService.getSalesOrderDetails(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'SALES_ORDERS')")
    public void delete(@PathVariable Long id) {
        salesOrderService.delete(id);
    }

    // --- Invoices for this order (gated by the INVOICES module, not SALES_ORDERS) -----------------

    /** Derived billing status + amount due per order, for the sales-orders list's payment column/filter. */
    @GetMapping("/payment-summaries")
    @PreAuthorize("@perm.canView(authentication, 'INVOICES')")
    public List<OrderPaymentSummaryDto> paymentSummaries() {
        return invoiceService.paymentSummaries();
    }

    @PostMapping("/{id}/invoice")
    @PreAuthorize("@perm.canCreate(authentication, 'INVOICES')")
    public InvoiceDetailsDto generateInvoice(@PathVariable Long id, @RequestBody(required = false) CreateInvoiceRequest request) {
        return invoiceService.generateForSalesOrder(id, request);
    }

    @GetMapping("/{id}/invoices")
    @PreAuthorize("@perm.canView(authentication, 'INVOICES')")
    public List<InvoiceDetailsDto> getInvoices(@PathVariable Long id) {
        return invoiceService.findBySalesOrder(id);
    }
}