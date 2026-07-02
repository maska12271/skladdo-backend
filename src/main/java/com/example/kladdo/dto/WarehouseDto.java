package com.example.kladdo.dto;

import com.example.kladdo.model.Warehouse;

public record WarehouseDto(
        Long id,
        String name,
        String address,
        Boolean active
) {
    public static WarehouseDto from(Warehouse w) {
        return new WarehouseDto(w.getId(), w.getName(), w.getAddress(), w.getActive());
    }
}
