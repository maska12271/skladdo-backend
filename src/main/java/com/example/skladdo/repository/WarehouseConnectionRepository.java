package com.example.skladdo.repository;

import com.example.skladdo.model.ConnectionStatus;
import com.example.skladdo.model.WarehouseConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link WarehouseConnection} spans two tenants and carries no {@code @TenantId}, so every method here
 * states which side it is querying for. Callers must always pass a company id the caller is entitled to -
 * never a raw id from the request body.
 */
public interface WarehouseConnectionRepository extends JpaRepository<WarehouseConnection, Long> {

    /** Everything a warehouse company is involved in, newest first (its "client companies" list). */
    List<WarehouseConnection> findByWarehouseCompanyIdOrderByIdDesc(Long warehouseCompanyId);

    /** Everything a client company is involved in, newest first (its "warehouse partners" list). */
    List<WarehouseConnection> findByClientCompanyIdOrderByIdDesc(Long clientCompanyId);

    /** The client companies a warehouse company's staff may currently switch into. */
    List<WarehouseConnection> findByWarehouseCompanyIdAndStatus(Long warehouseCompanyId, ConnectionStatus status);

    /**
     * The link behind every switched request. Also what stops a redeemed code from stacking a second
     * connection on top of a pair that already has one.
     */
    Optional<WarehouseConnection> findFirstByWarehouseCompanyIdAndClientCompanyIdAndStatus(
            Long warehouseCompanyId, Long clientCompanyId, ConnectionStatus status);
}
