package com.example.kladdo.repository;

import com.example.kladdo.model.SalesOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long>, JpaSpecificationExecutor<SalesOrder> {

    /** Sales orders authored by a given user (company-scoped automatically via the tenant filter). */
    List<SalesOrder> findByCreatedById(Long createdById);

    /** Sales orders linked to a tender, newest first. */
    List<SalesOrder> findByTenderIdOrderByIdDesc(Long tenderId);

    /** Currency codes ordered by how often they're used, for pre-selecting the most common one. */
    @Query("select o.currency from SalesOrder o where o.currency is not null group by o.currency order by count(o) desc")
    List<String> findMostUsedCurrencies(Pageable pageable);

    /**
     * For the stock ledger: {@code [orderId, orderNumber, totalQuantity]} for every order that ships the
     * given product out of the given warehouse. Returns the aggregate rather than the entities so the
     * order's item collection is never partially loaded by a filtered {@code join fetch}.
     */
    @Query("""
            select o.id, o.orderNumber, sum(i.quantity)
            from SalesOrder o join o.items i
            where o.warehouse.id = :warehouseId and i.product.id = :productId
            group by o.id, o.orderNumber
            """)
    List<Object[]> findOrderedQuantitiesForProduct(@Param("warehouseId") Long warehouseId,
                                                   @Param("productId") Long productId);
}