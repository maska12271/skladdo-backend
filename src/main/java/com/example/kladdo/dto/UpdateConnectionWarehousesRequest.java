package com.example.kladdo.dto;

import java.util.List;

/**
 * Which of the client's own warehouses a partner may work in. The complete set is sent every time, so an
 * empty or absent list shuts the partner out of every warehouse without ending the connection.
 */
public record UpdateConnectionWarehousesRequest(
        List<Long> warehouseIds
) {
}
