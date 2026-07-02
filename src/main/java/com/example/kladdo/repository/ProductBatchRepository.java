package com.example.kladdo.repository;

import com.example.kladdo.model.ProductBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductBatchRepository extends JpaRepository<ProductBatch, Long> {

    /** Existing lot for a product in a warehouse — used to top up on re-receipt instead of duplicating. */
    Optional<ProductBatch> findByProductIdAndWarehouseIdAndLotNumber(Long productId, Long warehouseId, String lotNumber);

    /** Any lot with this number for a product (across warehouses) — used to look up its shared dates. */
    Optional<ProductBatch> findFirstByProductIdAndLotNumberOrderByIdAsc(Long productId, String lotNumber);

    /**
     * FEFO order: nearest expiry first; lots without an expiry date sort last (treated as
     * "never expires"); among equal/absent expiries, oldest receipt first.
     */
    @Query("SELECT b FROM ProductBatch b WHERE b.product.id = :productId AND b.warehouse.id = :warehouseId " +
           "AND b.quantity > 0 ORDER BY b.expiryDate ASC NULLS LAST, b.createdAt ASC, b.id ASC")
    List<ProductBatch> findAvailableFefo(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    /** FIFO order: oldest receipt first. */
    @Query("SELECT b FROM ProductBatch b WHERE b.product.id = :productId AND b.warehouse.id = :warehouseId " +
           "AND b.quantity > 0 ORDER BY b.createdAt ASC, b.id ASC")
    List<ProductBatch> findAvailableFifo(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    /** LIFO order: newest receipt first. */
    @Query("SELECT b FROM ProductBatch b WHERE b.product.id = :productId AND b.warehouse.id = :warehouseId " +
           "AND b.quantity > 0 ORDER BY b.createdAt DESC, b.id DESC")
    List<ProductBatch> findAvailableLifo(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    /** All in-stock lots of a product across warehouses, for the product detail breakdown. */
    @Query("SELECT b FROM ProductBatch b JOIN FETCH b.warehouse WHERE b.product.id = :productId " +
           "AND b.quantity > 0 ORDER BY b.expiryDate ASC NULLS LAST, b.lotNumber ASC")
    List<ProductBatch> findInStockByProductId(@Param("productId") Long productId);

    /**
     * In-stock lots whose expiry date is on or before {@code cutoff} — i.e. already expired or
     * expiring within the dashboard horizon. Ordered soonest-first (expired lots lead). Lots with no
     * expiry date are excluded.
     */
    @Query("SELECT b FROM ProductBatch b JOIN FETCH b.product p JOIN FETCH b.warehouse w " +
           "WHERE b.quantity > 0 AND b.expiryDate IS NOT NULL AND b.expiryDate <= :cutoff " +
           "ORDER BY b.expiryDate ASC")
    List<ProductBatch> findExpiringByCutoff(@Param("cutoff") LocalDate cutoff);

    void deleteByProductId(Long productId);
}
