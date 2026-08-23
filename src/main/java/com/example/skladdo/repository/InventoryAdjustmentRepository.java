package com.example.skladdo.repository;

import com.example.skladdo.model.InventoryAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, Long> {

    /** Adjustment history for one product, newest first. Scoped to the current company by {@code @TenantId}. */
    List<InventoryAdjustment> findByProductIdOrderByCreatedAtDescIdDesc(Long productId);

    /** Adjustments made by one user, newest first. Powers the warehouse profile's activity view. */
    List<InventoryAdjustment> findByCreatedByIdOrderByCreatedAtDescIdDesc(Long createdById);
}
