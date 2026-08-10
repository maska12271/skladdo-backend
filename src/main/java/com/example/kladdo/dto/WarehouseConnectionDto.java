package com.example.kladdo.dto;

import com.example.kladdo.model.ConnectionStatus;

import java.time.Instant;
import java.util.List;

/**
 * A warehouse connection as seen by one of its two companies. The same row renders on both sides, so
 * {@code viewedAsPartner} is what tells the UI which way round to label it and which controls to show -
 * assigning warehouses and price visibility belong to the client alone.
 */
public record WarehouseConnectionDto(
        Long id,
        ConnectionStatus status,
        boolean canSeePrices,

        Long warehouseCompanyId,
        String warehouseCompanyName,

        Long clientCompanyId,
        String clientCompanyName,

        /** The client's warehouses this partner may work in. Empty until the client assigns some. */
        List<AssignedWarehouseDto> warehouses,

        /** True when the reading company is the warehouse partner rather than the client. */
        boolean viewedAsPartner,

        Instant createdAt,
        Instant revokedAt
) {
    /** One warehouse a connection covers. Always a warehouse of the client's own. */
    public record AssignedWarehouseDto(Long id, String name) {
    }
}
