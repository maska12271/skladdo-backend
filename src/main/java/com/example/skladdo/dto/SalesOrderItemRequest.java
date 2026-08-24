package com.example.skladdo.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public class SalesOrderItemRequest {

    /**
     * The product being sold, or null when this line sells a {@link #serviceId} instead. Exactly one of
     * the two must be set - a cross-field rule, so it is enforced in {@code SalesOrderService} (with a
     * translated {@code error.order.itemProductOrService}) rather than by a bean-validation annotation.
     */
    private Long productId;

    /** The service being sold, or null for an ordinary product line. See {@link #productId}. */
    private Long serviceId;

    @NotNull
    @Min(1)
    private Integer quantity;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal unitPrice;

    @DecimalMin("0.0")
    private BigDecimal discountPercent;

    @DecimalMin("0.0")
    private BigDecimal taxRatePercent;

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(BigDecimal discountPercent) {
        this.discountPercent = discountPercent;
    }

    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    public void setTaxRatePercent(BigDecimal taxRatePercent) {
        this.taxRatePercent = taxRatePercent;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }
}