package com.example.tenderapp.controller;

import com.example.tenderapp.dto.CreatePurchaseOrderRequest;
import com.example.tenderapp.dto.OrderDetailsDto;
import com.example.tenderapp.dto.UpdatePurchaseOrderRequest;
import com.example.tenderapp.dto.UpdateStatusRequest;
import com.example.tenderapp.model.OrderStatus;
import com.example.tenderapp.model.PurchaseOrder;
import com.example.tenderapp.service.OrderAnalyticsService;
import com.example.tenderapp.service.PurchaseOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/purchase-orders")
@Tag(name = "Purchase Orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final OrderAnalyticsService orderAnalyticsService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService,
                                   OrderAnalyticsService orderAnalyticsService) {
        this.purchaseOrderService = purchaseOrderService;
        this.orderAnalyticsService = orderAnalyticsService;
    }

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'PURCHASE_ORDERS')")
    public Page<PurchaseOrder> getAll(
            @RequestParam(required = false) Long manufacturerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return purchaseOrderService.findAll(manufacturerId, status, dateFrom, dateTo, pageable);
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

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'PURCHASE_ORDERS')")
    public void delete(@PathVariable Long id) {
        purchaseOrderService.delete(id);
    }
}