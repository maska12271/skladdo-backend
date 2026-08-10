package com.example.kladdo.repository;

import com.example.kladdo.model.PurchaseOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long>, JpaSpecificationExecutor<PurchaseOrder> {

    /** Purchase orders authored by a given user (company-scoped automatically via the tenant filter). */
    List<PurchaseOrder> findByCreatedById(Long createdById);

    /** Purchase orders linked to a tender, newest first. */
    List<PurchaseOrder> findByTenderIdOrderByIdDesc(Long tenderId);

    /** Currency codes ordered by how often they're used, for pre-selecting the most common one. */
    @Query("select o.currency from PurchaseOrder o where o.currency is not null group by o.currency order by count(o) desc")
    List<String> findMostUsedCurrencies(Pageable pageable);

    /**
     * For the stock ledger: {@code [orderId, orderNumber, totalQuantity]} for every order that receives the
     * given product into the given warehouse. Aggregated rather than entity-loaded for the same reason as
     * the sales-side query.
     */
    @Query("""
            select o.id, o.orderNumber, sum(i.quantity)
            from PurchaseOrder o join o.items i
            where o.warehouse.id = :warehouseId and i.product.id = :productId
            group by o.id, o.orderNumber
            """)
    List<Object[]> findOrderedQuantitiesForProduct(@org.springframework.data.repository.query.Param("warehouseId") Long warehouseId,
                                                   @org.springframework.data.repository.query.Param("productId") Long productId);
}