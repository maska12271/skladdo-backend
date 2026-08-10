package com.example.kladdo.repository;

import com.example.kladdo.model.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByProductId(Long productId);

    List<PurchaseOrderItem> findByPurchaseOrder_Manufacturer_Id(Long manufacturerId);

    /**
     * {@code [productId, unitPrice, exchangeRate]} for the given products, most recently ordered first, so
     * the reorder view can pre-fill each line with what was last paid. {@code exchangeRate} lets the caller
     * convert to the company's base currency (via {@code MoneyConverter.toBase}) - the order that set this
     * price may have been placed in a foreign currency, and the reorder view and the draft it creates are
     * always in base currency. One query for the whole page; the caller keeps the first row it sees per
     * product.
     */
    @org.springframework.data.jpa.repository.Query("""
            select i.product.id, i.unitPrice, i.purchaseOrder.exchangeRate
            from PurchaseOrderItem i
            where i.product.id in :productIds
            order by i.purchaseOrder.orderDate desc, i.purchaseOrder.id desc
            """)
    List<Object[]> findRecentPricesForProducts(
            @org.springframework.data.repository.query.Param("productIds") java.util.Collection<Long> productIds);
}