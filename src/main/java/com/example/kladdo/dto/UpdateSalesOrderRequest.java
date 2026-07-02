package com.example.kladdo.dto;

import com.example.kladdo.model.OrderStatus;
import com.example.kladdo.model.PenaltyPeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class UpdateSalesOrderRequest {

    @NotNull
    private Long clientId;

    @NotNull
    private Long warehouseId;

    private String orderNumber;
    private OrderStatus status;
    private String deliveryAddress;
    private String notes;
    private LocalDate orderDate;
    private LocalDate closingDate;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal deliveryPrice;

    // Optional per-order payment-term overrides; null means "use the company settings".
    private Integer paymentTermDays;
    private BigDecimal penaltyPercent;
    private PenaltyPeriod penaltyPeriod;

    @Valid
    @NotEmpty
    private List<SalesOrderItemRequest> items;

    public Integer getPaymentTermDays() {
        return paymentTermDays;
    }

    public void setPaymentTermDays(Integer paymentTermDays) {
        this.paymentTermDays = paymentTermDays;
    }

    public BigDecimal getPenaltyPercent() {
        return penaltyPercent;
    }

    public void setPenaltyPercent(BigDecimal penaltyPercent) {
        this.penaltyPercent = penaltyPercent;
    }

    public PenaltyPeriod getPenaltyPeriod() {
        return penaltyPeriod;
    }

    public void setPenaltyPeriod(PenaltyPeriod penaltyPeriod) {
        this.penaltyPeriod = penaltyPeriod;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
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

    public BigDecimal getDeliveryPrice() {
        return deliveryPrice;
    }

    public void setDeliveryPrice(BigDecimal deliveryPrice) {
        this.deliveryPrice = deliveryPrice;
    }

    public List<SalesOrderItemRequest> getItems() {
        return items;
    }

    public void setItems(List<SalesOrderItemRequest> items) {
        this.items = items;
    }
}