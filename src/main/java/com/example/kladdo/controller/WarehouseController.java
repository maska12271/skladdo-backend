package com.example.kladdo.controller;

import com.example.kladdo.dto.CreateWarehouseRequest;
import com.example.kladdo.dto.UpdateWarehouseRequest;
import com.example.kladdo.dto.WarehouseDto;
import com.example.kladdo.dto.WarehouseStockItemDto;
import com.example.kladdo.service.WarehouseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warehouses")
@Tag(name = "Warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'WAREHOUSES')")
    public List<WarehouseDto> getAll() {
        return warehouseService.findAllForCurrentUser();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canView(authentication, 'WAREHOUSES')")
    public WarehouseDto getById(@PathVariable Long id) {
        return warehouseService.findById(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'WAREHOUSES')")
    public WarehouseDto create(@Valid @RequestBody CreateWarehouseRequest request) {
        return warehouseService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'WAREHOUSES')")
    public WarehouseDto update(@PathVariable Long id, @Valid @RequestBody UpdateWarehouseRequest request) {
        return warehouseService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'WAREHOUSES')")
    public void delete(@PathVariable Long id) {
        warehouseService.delete(id);
    }

    @GetMapping("/{id}/stock")
    @PreAuthorize("@perm.canView(authentication, 'WAREHOUSES')")
    public Page<WarehouseStockItemDto> getStock(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        // The repository query already sorts by product name; passing a Sort here would append an
        // invalid path (productName lives on the DTO, not the WarehouseStock entity).
        Pageable pageable = PageRequest.of(page, size);
        return warehouseService.getStockForWarehouse(id, pageable);
    }

    /**
     * {@code productId -> quantity} map for a warehouse, used by the sales/purchase order forms to show
     * available stock per line. Gated like the warehouse list ({@code canReadReference}) so users who can
     * build orders but lack the full warehouse page can still see how much stock a warehouse holds.
     */
    @GetMapping("/{id}/stock-levels")
    @PreAuthorize("@perm.canReadReference(authentication, 'WAREHOUSES')")
    public Map<Long, Integer> getStockLevels(@PathVariable Long id) {
        return warehouseService.getStockLevels(id);
    }
}
