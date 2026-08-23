package com.example.skladdo.repository;

import com.example.skladdo.model.Warehouse;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Batch-loads warehouses across tenants by id, for naming the warehouses a
     * {@link com.example.skladdo.model.WarehouseConnection} covers: they live in the client's tenant, which
     * the partner reading the list cannot reach with an ordinary tenant-scoped query. Deliberately a
     * <strong>native</strong> query, because the {@code @TenantId} discriminator is fixed when the
     * Hibernate session opens and would hide them. Callers must only ever pass ids taken from connections
     * the caller is a party to.
     */
    @Query(value = "SELECT * FROM warehouse WHERE id IN (:ids)", nativeQuery = true)
    List<Warehouse> findAllByIdInIgnoringTenant(@Param("ids") Collection<Long> ids);
}
