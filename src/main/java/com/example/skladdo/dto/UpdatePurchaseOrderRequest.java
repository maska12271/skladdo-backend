package com.example.skladdo.dto;

import com.example.skladdo.model.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class UpdatePurchaseOrderRequest {

    @NotNull
    private Long manufacturerId;

    @NotNull
    private Long warehouseId;

    private String orderNumber;
    private OrderStatus status;
    private String deliveryAddress;
    private String notes;
    private LocalDate orderDate;
    private LocalDate closingDate;
    private LocalDate expectedDeliveryDate;

    // Optional attached supplier invoice document (uploaded separately; null = none).
    private String invoiceFileKey;
    private String invoiceFileName;

    // ISO 4217 currency for this order's amounts; null/blank = the company base currency.
    private String currency;

    // Snapshotted rate: 1 base currency = exchangeRate units of `currency`. Null when in base currency.
    private BigDecimal exchangeRate;

    // Optional tender this order belongs to (null = not linked to a tender).
    private Long tenderId;

    // Optional contact person at the manufacturer this order is addressed to (null = the company itself).
    private Long contactId;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal deliveryPrice;

    @Valid
    @NotEmpty
    private List<PurchaseOrderItemRequest> items;

    public Long getManufacturerId() {
        return manufacturerId;
    }

    public void setManufacturerId(Long manufacturerId) {
        this.manufacturerId = manufacturerId;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(Long warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDate orderDate) {
        this.orderDate = orderDate;
    }

    public LocalDate getClosingDate() {
        return closingDate;
    }

    public void setClosingDate(LocalDate closingDate) {
        this.closingDate = closingDate;
    }

    public LocalDate getExpectedDeliveryDate() {
        return expectedDeliveryDate;
    }

    public void setExpectedDeliveryDate(LocalDate expectedDeliveryDate) {
        this.expectedDeliveryDate = expectedDeliveryDate;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    public Long getTenderId() {
        return tenderId;
    }

    public void setTenderId(Long tenderId) {
        this.tenderId = tenderId;
    }

    public Long getContactId() {
        return contactId;
    }

    public void setContactId(Long contactId) {
        this.contactId = contactId;
    }

    public BigDecimal getDeliveryPrice() {
        return deliveryPrice;
    }

    public void setDeliveryPrice(BigDecimal deliveryPrice) {
        this.deliveryPrice = deliveryPrice;
    }

    public List<PurchaseOrderItemRequest> getItems() {
        return items;
    }

    public void setItems(List<PurchaseOrderItemRequest> items) {
        this.items = items;
    }

    public String getInvoiceFileKey() {
        return invoiceFileKey;
    }

    public void setInvoiceFileKey(String invoiceFileKey) {
        this.invoiceFileKey = invoiceFileKey;
    }

    public String getInvoiceFileName() {
        return invoiceFileName;
    }

    public void setInvoiceFileName(String invoiceFileName) {
        this.invoiceFileName = invoiceFileName;
    }
}