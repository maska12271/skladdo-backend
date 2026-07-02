package com.example.kladdo.repository;

import com.example.kladdo.model.WarehouseStock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WarehouseStockRepository extends JpaRepository<WarehouseStock, Long> {

    Optional<WarehouseStock> findByWarehouseIdAndProductId(Long warehouseId, Long productId);

    /** Stock rows for all products in a warehouse, joined with product data for display. */
    @Query("SELECT ws FROM WarehouseStock ws JOIN FETCH ws.product p WHERE ws.warehouse.id = :warehouseId ORDER BY p.name ASC")
    Page<WarehouseStock> findByWarehouseIdWithProduct(@Param("warehouseId") Long warehouseId, Pageable pageable);

    /** Per-warehouse stock for one product, for the per-warehouse breakdown on the product detail page. */
    @Query("SELECT ws FROM WarehouseStock ws JOIN FETCH ws.warehouse WHERE ws.product.id = :productId ORDER BY ws.warehouse.name ASC")
    List<WarehouseStock> findByProductIdWithWarehouse(@Param("productId") Long productId);

    /** Sum of all warehouse quantities for a product (= total stock). Returns 0 when no rows exist. */
    @Query("SELECT COALESCE(SUM(ws.quantity), 0) FROM WarehouseStock ws WHERE ws.product.id = :productId")
    int sumQuantityByProductId(@Param("productId") Long productId);

    void deleteByProductId(Long productId);
}
