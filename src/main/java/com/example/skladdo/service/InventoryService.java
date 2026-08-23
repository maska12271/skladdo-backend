package com.example.skladdo.service;

import com.example.skladdo.dto.CreateInventoryAdjustmentRequest;
import com.example.skladdo.dto.CreateProductBatchRequest;
import com.example.skladdo.dto.InventoryAdjustmentDto;
import com.example.skladdo.dto.LotAdjustmentRequest;
import com.example.skladdo.dto.LotLookupDto;
import com.example.skladdo.dto.ProductBatchDto;
import com.example.skladdo.dto.UpdateProductBatchRequest;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.InventoryAdjustment;
import com.example.skladdo.model.Product;
import com.example.skladdo.model.ProductBatch;
import com.example.skladdo.model.User;
import com.example.skladdo.model.Warehouse;
import com.example.skladdo.repository.InventoryAdjustmentRepository;
import com.example.skladdo.repository.ProductBatchRepository;
import com.example.skladdo.repository.ProductRepository;
import com.example.skladdo.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class InventoryService {

    private final ProductRepository productRepository;
    private final InventoryAdjustmentRepository adjustmentRepository;
    private final UserRepository userRepository;
    private final WarehouseService warehouseService;
    private final ProductBatchRepository productBatchRepository;
    private final ProductBatchService productBatchService;

    public InventoryService(ProductRepository productRepository,
                            InventoryAdjustmentRepository adjustmentRepository,
                            UserRepository userRepository,
                            WarehouseService warehouseService,
                            ProductBatchRepository productBatchRepository,
                            ProductBatchService productBatchService) {
        this.productRepository = productRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.userRepository = userRepository;
        this.warehouseService = warehouseService;
        this.productBatchRepository = productBatchRepository;
        this.productBatchService = productBatchService;
    }

    /** Adjusts a product's stock by the (signed) requested amount in the specified warehouse. */
    @Transactional
    public InventoryAdjustmentDto adjust(Long productId, CreateInventoryAdjustmentRequest request) {
        Integer change = request.quantityChange();
        if (change == null || change == 0) {
            throw new BadRequestException("error.inventory.quantityChangeNonZero");
        }

        Product product = requireProduct(productId);
        Warehouse warehouse = warehouseService.requireAccess(request.warehouseId());

        // Delegates the stock mutation + product total sync to WarehouseService.
        warehouseService.adjustWarehouseStock(warehouse, product, change);

        // Reload to get the updated stockQuantity written by adjustWarehouseStock.
        product = requireProduct(productId);

        InventoryAdjustment adjustment = new InventoryAdjustment();
        adjustment.setProduct(product);
        adjustment.setWarehouse(warehouse);
        adjustment.setQuantityChange(change);
        adjustment.setNewQuantity(product.getStockQuantity());
        adjustment.setNote(request.note() != null && !request.note().isBlank() ? request.note().trim() : null);
        InventoryAdjustment saved = adjustmentRepository.save(adjustment);

        return InventoryAdjustmentDto.from(saved, actorFor(saved.getCreatedById(), new HashMap<>()));
    }

    /**
     * Receives stock into a lot: creates the lot in the warehouse (or tops it up) and bumps the
     * warehouse + product totals, logging an adjustment for the audit trail. When the product already
     * has a lot with this number, that lot's dates are reused so its identity stays consistent.
     */
    @Transactional
    public InventoryAdjustmentDto receiveLot(Long productId, CreateProductBatchRequest request) {
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new BadRequestException("error.quantityPositive");
        }
        if (request.lotNumber() == null || request.lotNumber().isBlank()) {
            throw new BadRequestException("error.inventory.lotNumberRequired");
        }
        Product product = requireProduct(productId);
        Warehouse warehouse = warehouseService.requireAccess(request.warehouseId());
        String lot = request.lotNumber().trim();

        LocalDate productionDate = request.productionDate();
        LocalDate expiryDate = request.expiryDate();
        Optional<ProductBatch> existing = productBatchRepository.findFirstByProductIdAndLotNumberOrderByIdAsc(productId, lot);
        if (existing.isPresent()) {
            productionDate = existing.get().getProductionDate();
            expiryDate = existing.get().getExpiryDate();
        }

        productBatchService.receiveBatch(product, warehouse, lot, request.quantity(), productionDate, expiryDate);
        warehouseService.adjustWarehouseStock(warehouse, product, request.quantity());
        product = requireProduct(productId);

        String note = request.note() != null && !request.note().isBlank()
                ? request.note().trim() : "Received lot " + lot;
        InventoryAdjustment adjustment = newAdjustment(product, warehouse, request.quantity(), note);
        return InventoryAdjustmentDto.from(adjustmentRepository.save(adjustment), actorFor(adjustment.getCreatedById(), new HashMap<>()));
    }

    /** Applies a signed change to each listed lot, keeping warehouse + product totals in sync. */
    @Transactional
    public void adjustLots(Long productId, LotAdjustmentRequest request) {
        Product product = requireProduct(productId);
        String reason = request.note() != null && !request.note().isBlank() ? request.note().trim() : null;
        boolean anyApplied = false;

        for (LotAdjustmentRequest.Item item : request.items()) {
            if (item.quantityChange() == null || item.quantityChange() == 0) {
                continue;
            }
            ProductBatch batch = productBatchRepository.findById(item.batchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lot not found with id: " + item.batchId()));
            if (batch.getProduct() == null || !batch.getProduct().getId().equals(productId)) {
                throw new BadRequestException("error.inventory.lotNotInProduct");
            }
            Warehouse warehouse = warehouseService.requireAccess(batch.getWarehouse().getId());

            int newQty = batch.getQuantity() + item.quantityChange();
            if (newQty < 0) {
                throw new BadRequestException("error.inventory.lotRemoveExceeds",
                        batch.getLotNumber(), batch.getQuantity());
            }
            batch.setQuantity(newQty);
            productBatchRepository.save(batch);

            warehouseService.adjustWarehouseStock(warehouse, product, item.quantityChange());
            product = requireProduct(productId);

            String note = (reason != null ? reason + " · " : "") + "lot " + batch.getLotNumber();
            adjustmentRepository.save(newAdjustment(product, warehouse, item.quantityChange(), note));
            anyApplied = true;
        }

        if (!anyApplied) {
            throw new BadRequestException("error.inventory.noAdjustment");
        }
    }

    /** Edits a lot's identifying details (number/dates). Quantity is unchanged. */
    @Transactional
    public ProductBatchDto editLot(Long productId, Long batchId, UpdateProductBatchRequest request) {
        ProductBatch batch = productBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Lot not found with id: " + batchId));
        if (batch.getProduct() == null || !batch.getProduct().getId().equals(productId)) {
            throw new BadRequestException("error.inventory.lotNotInProduct");
        }
        warehouseService.requireAccess(batch.getWarehouse().getId());

        String lot = request.lotNumber() != null ? request.lotNumber().trim() : "";
        if (lot.isBlank()) {
            throw new BadRequestException("error.inventory.lotNumberRequired");
        }
        if (!lot.equals(batch.getLotNumber())) {
            productBatchRepository.findByProductIdAndWarehouseIdAndLotNumber(productId, batch.getWarehouse().getId(), lot)
                    .ifPresent(other -> {
                        throw new BadRequestException("error.inventory.lotExists", lot);
                    });
            batch.setLotNumber(lot);
        }
        batch.setProductionDate(request.productionDate());
        batch.setExpiryDate(request.expiryDate());
        return ProductBatchDto.from(productBatchRepository.save(batch));
    }

    /** Looks up whether the product already has a lot with this number, returning its shared dates. */
    @Transactional(readOnly = true)
    public LotLookupDto lookupLot(Long productId, String lotNumber) {
        requireProduct(productId);
        if (lotNumber == null || lotNumber.isBlank()) {
            return new LotLookupDto(false, null, null);
        }
        return productBatchRepository.findFirstByProductIdAndLotNumberOrderByIdAsc(productId, lotNumber.trim())
                .map(b -> new LotLookupDto(true, b.getProductionDate(), b.getExpiryDate()))
                .orElse(new LotLookupDto(false, null, null));
    }

    private InventoryAdjustment newAdjustment(Product product, Warehouse warehouse, int change, String note) {
        InventoryAdjustment adjustment = new InventoryAdjustment();
        adjustment.setProduct(product);
        adjustment.setWarehouse(warehouse);
        adjustment.setQuantityChange(change);
        adjustment.setNewQuantity(product.getStockQuantity());
        adjustment.setNote(note);
        return adjustment;
    }

    /** A product's adjustment history, newest first. */
    @Transactional(readOnly = true)
    public List<InventoryAdjustmentDto> historyForProduct(Long productId) {
        requireProduct(productId);
        Map<Long, InventoryAdjustmentDto.Actor> actorCache = new HashMap<>();
        return adjustmentRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId).stream()
                .map(a -> InventoryAdjustmentDto.from(a, actorFor(a.getCreatedById(), actorCache)))
                .toList();
    }

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));
    }

    private InventoryAdjustmentDto.Actor actorFor(Long userId, Map<Long, InventoryAdjustmentDto.Actor> cache) {
        if (userId == null) {
            return null;
        }
        return cache.computeIfAbsent(userId, id -> userRepository.findById(id)
                .map(this::toActor)
                .orElse(null));
    }

    private InventoryAdjustmentDto.Actor toActor(User user) {
        String name = user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName()
                : user.getEmail();
        return new InventoryAdjustmentDto.Actor(user.getId(), name);
    }
}
