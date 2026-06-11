package com.example.tenderapp.controller;

import com.example.tenderapp.dto.CreateSalesOrderRequest;
import com.example.tenderapp.dto.UpdateSalesOrderRequest;
import com.example.tenderapp.model.OrderStatus;
import com.example.tenderapp.model.SalesOrder;
import com.example.tenderapp.service.SalesOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/sales-orders")
@Tag(name = "Sales Orders")
public class SalesOrderController {

    private final SalesOrderService salesOrderService;

    public SalesOrderController(SalesOrderService salesOrderService) {
        this.salesOrderService = salesOrderService;
    }

    @GetMapping
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
    public SalesOrder getById(@PathVariable Long id) {
        return salesOrderService.findById(id);
    }

    @PostMapping
    public SalesOrder create(@Valid @RequestBody CreateSalesOrderRequest request) {
        return salesOrderService.create(request);
    }

    @PutMapping("/{id}")
    public SalesOrder update(@PathVariable Long id, @Valid @RequestBody UpdateSalesOrderRequest request) {
        return salesOrderService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        salesOrderService.delete(id);
    }
}