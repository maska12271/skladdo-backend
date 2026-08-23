package com.example.skladdo.controller;

import com.example.skladdo.dto.CreateInventoryAdjustmentRequest;
import com.example.skladdo.dto.CreateProductBatchRequest;
import com.example.skladdo.dto.CreateStockTransferRequest;
import com.example.skladdo.dto.InventoryAdjustmentDto;
import com.example.skladdo.dto.LotAdjustmentRequest;
import com.example.skladdo.dto.LotLookupDto;
import com.example.skladdo.dto.ProductBatchDto;
import com.example.skladdo.dto.ProductDetailsDto;
import com.example.skladdo.dto.ProductWarehouseStockDto;
import com.example.skladdo.dto.ReorderSuggestionDto;
import com.example.skladdo.dto.StockTransferDto;
import com.example.skladdo.dto.UpdateProductBatchRequest;
import com.example.skladdo.dto.UpdateProductImagesRequest;
import com.example.skladdo.model.Product;
import com.example.skladdo.service.InventoryService;
import com.example.skladdo.service.ProductAnalyticsService;
import com.example.skladdo.service.ProductService;
import com.example.skladdo.service.ReorderService;
import com.example.skladdo.service.StockTransferService;
import com.example.skladdo.service.WarehouseService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Products")
public class ProductController {

    private final ProductService productService;
    private final ProductAnalyticsService productAnalyticsService;
    private final InventoryService inventoryService;
    private final WarehouseService warehouseService;
    private final StockTransferService stockTransferService;
    private final ReorderService reorderService;

    public ProductController(ProductService productService,
                             ProductAnalyticsService productAnalyticsService,
                             InventoryService inventoryService,
                             WarehouseService warehouseService,
                             StockTransferService stockTransferService,
                             ReorderService reorderService) {
        this.productService = productService;
        this.productAnalyticsService = productAnalyticsService;
        this.inventoryService = inventoryService;
        this.warehouseService = warehouseService;
        this.stockTransferService = stockTransferService;
        this.reorderService = reorderService;
    }

    /**
     * Products at or below their minimum stock, with a suggested order quantity and the price last paid.
     * Read-only: turning these into an order goes through the normal purchase-order endpoint.
     */
    @GetMapping("/reorder-suggestions")
    @PreAuthorize("@perm.canView(authentication, 'PRODUCTS')")
    public List<ReorderSuggestionDto> getReorderSuggestions() {
        return reorderService.suggestions();
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public Page<Product> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<Long> categoryId,
            @RequestParam(required = false) List<Long> manufacturerId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) List<String> stockStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return productService.search(search, categoryId, manufacturerId, active, stockStatus, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public Product getById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @GetMapping("/{id}/details")
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public ProductDetailsDto getDetails(@PathVariable Long id) {
        return productAnalyticsService.getDetails(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'PRODUCTS')")
    public Product create(@Valid @RequestBody Product product) {
        return productService.save(product);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'PRODUCTS')")
    public Product update(@PathVariable Long id, @Valid @RequestBody Product product) {
        return productService.update(id, product);
    }

    /**
     * Replace a product's images. Separate from the full update so the detail page's gallery can add or
     * drop a photo without round-tripping every product field — which it cannot do anyway in a session
     * where prices are redacted out of the response it would have to send back.
     */
    @PutMapping("/{id}/images")
    @PreAuthorize("@perm.canEdit(authentication, 'PRODUCTS')")
    public Product updateImages(@PathVariable Long id, @RequestBody UpdateProductImagesRequest request) {
        return productService.updateImages(id, request.imageKeys());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'PRODUCTS')")
    public void delete(@PathVariable Long id) {
        productService.delete(id);
    }

    // --- Inventory (stock-taking) ----------------------------------------------------------------

    /** A product's stock-adjustment history. Visible to anyone who can read the product. */
    @GetMapping("/{id}/adjustments")
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public List<InventoryAdjustmentDto> getAdjustments(@PathVariable Long id) {
        return inventoryService.historyForProduct(id);
    }

    /** Adjust a product's stock up or down with a reason. Requires the INVENTORY create permission. */
    @PostMapping("/{id}/adjustments")
    @PreAuthorize("@perm.canCreate(authentication, 'INVENTORY')")
    public InventoryAdjustmentDto createAdjustment(@PathVariable Long id,
                                                   @Valid @RequestBody CreateInventoryAdjustmentRequest request) {
        return inventoryService.adjust(id, request);
    }

    /** Per-warehouse stock breakdown for a product. Visible to anyone who can read the product. */
    @GetMapping("/{id}/warehouse-stock")
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public List<ProductWarehouseStockDto> getWarehouseStock(@PathVariable Long id) {
        return warehouseService.getWarehouseStockForProduct(id);
    }

    /** In-stock lots (batches) for a product, with quantity and production/expiry dates. */
    @GetMapping("/{id}/batches")
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public List<ProductBatchDto> getBatches(@PathVariable Long id) {
        return warehouseService.getBatchesForProduct(id);
    }

    /** Whether the product already has a lot with the given number (returns its shared dates if so). */
    @GetMapping("/{id}/batches/lookup")
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public LotLookupDto lookupLot(@PathVariable Long id, @RequestParam String lotNumber) {
        return inventoryService.lookupLot(id, lotNumber);
    }

    /** Receive stock into a lot (create or top up), bumping the warehouse + product totals. */
    @PostMapping("/{id}/batches")
    @PreAuthorize("@perm.canCreate(authentication, 'INVENTORY')")
    public InventoryAdjustmentDto receiveLot(@PathVariable Long id, @Valid @RequestBody CreateProductBatchRequest request) {
        return inventoryService.receiveLot(id, request);
    }

    /** Apply a signed stock change to one or more lots. */
    @PostMapping("/{id}/batches/adjust")
    @PreAuthorize("@perm.canCreate(authentication, 'INVENTORY')")
    public void adjustLots(@PathVariable Long id, @Valid @RequestBody LotAdjustmentRequest request) {
        inventoryService.adjustLots(id, request);
    }

    /** Edit a lot's number / production / expiry dates. */
    @PutMapping("/{id}/batches/{batchId}")
    @PreAuthorize("@perm.canCreate(authentication, 'INVENTORY')")
    public ProductBatchDto editLot(@PathVariable Long id, @PathVariable Long batchId,
                                   @Valid @RequestBody UpdateProductBatchRequest request) {
        return inventoryService.editLot(id, batchId, request);
    }

    // --- Stock transfers between warehouses ------------------------------------------------------

    /** A product's history of stock moved between warehouses, newest first. Visible to product readers. */
    @GetMapping("/{id}/transfers")
    @PreAuthorize("@perm.canReadReference(authentication, 'PRODUCTS')")
    public List<StockTransferDto> getTransfers(@PathVariable Long id) {
        return stockTransferService.historyForProduct(id);
    }

    /** Move stock of a product from one warehouse to another (optionally a specific lot). */
    @PostMapping("/{id}/transfers")
    @PreAuthorize("@perm.canCreate(authentication, 'INVENTORY')")
    public StockTransferDto transfer(@PathVariable Long id, @Valid @RequestBody CreateStockTransferRequest request) {
        return stockTransferService.transfer(id, request);
    }
}