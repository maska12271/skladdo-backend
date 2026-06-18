package com.example.tenderapp.controller;

import com.example.tenderapp.dto.CreateSalesOrderRequest;
import com.example.tenderapp.dto.OrderDetailsDto;
import com.example.tenderapp.dto.UpdateSalesOrderRequest;
import com.example.tenderapp.dto.UpdateStatusRequest;
import com.example.tenderapp.model.OrderStatus;
import com.example.tenderapp.model.SalesOrder;
import com.example.tenderapp.service.OrderAnalyticsService;
import com.example.tenderapp.service.SalesOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/sales-orders")
@Tag(name = "Sales Orders")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;
    private final OrderAnalyticsService orderAnalyticsService;

    public SalesOrderController(SalesOrderService salesOrderService,
                                OrderAnalyticsService orderAnalyticsService) {
        this.salesOrderService = salesOrderService;
        this.orderAnalyticsService = orderAnalyticsService;
    }

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'SALES_ORDERS')")
    public Page<SalesOrder> getAll(
            @RequestParam(required = false) Long clientId,
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
        return salesOrderService.findAll(clientId, status, dateFrom, dateTo, pageable);
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

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'SALES_ORDERS')")
    public void delete(@PathVariable Long id) {
        salesOrderService.delete(id);
    }
}