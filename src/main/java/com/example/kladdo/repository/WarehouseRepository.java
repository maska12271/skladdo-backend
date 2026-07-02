package com.example.kladdo.repository;

import com.example.kladdo.model.Warehouse;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    /** All warehouses the given user has been assigned to. Scoped to the current tenant by @TenantId on Warehouse. */
    @Query("SELECT w FROM Warehouse w WHERE w.id IN " +
           "(SELECT w2.id FROM User u JOIN u.warehouses w2 WHERE u.id = :userId)")
    List<Warehouse> findAssignedToUser(@Param("userId") Long userId);

    /** Inserts a user-warehouse assignment directly into the join table. Used by DataInitializer. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO user_warehouse (user_id, warehouse_id) VALUES (:userId, :warehouseId)", nativeQuery = true)
    void assignUserToWarehouse(@Param("userId") Long userId, @Param("warehouseId") Long warehouseId);

    /** Clears all user-warehouse assignments. Used only by DataInitializer wipeAll. */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM user_warehouse", nativeQuery = true)
    void clearAllWarehouseAssignments();
}
