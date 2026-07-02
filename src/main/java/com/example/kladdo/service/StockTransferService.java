package com.example.kladdo.service;

import com.example.kladdo.dto.CreateStockTransferRequest;
import com.example.kladdo.dto.StockTransferDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Product;
import com.example.kladdo.model.ProductBatch;
import com.example.kladdo.model.StockTransfer;
import com.example.kladdo.model.User;
import com.example.kladdo.model.Warehouse;
import com.example.kladdo.repository.ProductBatchRepository;
import com.example.kladdo.repository.ProductRepository;
import com.example.kladdo.repository.StockTransferRepository;
import com.example.kladdo.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves stock of a product between warehouses. A transfer is net-zero company-wide: it draws units out
 * of the source warehouse and adds them to the destination, keeping both {@link com.example.kladdo.model.WarehouseStock}
 * totals and (when a lot is chosen) the {@link ProductBatch} breakdown in sync. The hard
 * "enough stock in the source" guard is enforced by {@code WarehouseService.adjustWarehouseStock};
 * lot identity (production/expiry dates) is preserved by re-receiving the same lot in the destination.
 */
@Service
public class StockTransferService {

    private final ProductRepository productRepository;
    private final ProductBatchRepository productBatchRepository;
    private final StockTransferRepository transferRepository;
    private final UserRepository userRepository;
    private final WarehouseService warehouseService;
    private final ProductBatchService productBatchService;

    public StockTransferService(ProductRepository productRepository,
                                ProductBatchRepository productBatchRepository,
                                StockTransferRepository transferRepository,
                                UserRepository userRepository,
                                WarehouseService warehouseService,
                                ProductBatchService productBatchService) {
        this.productRepository = productRepository;
        this.productBatchRepository = productBatchRepository;
        this.transferRepository = transferRepository;
        this.userRepository = userRepository;
        this.warehouseService = warehouseService;
        this.productBatchService = productBatchService;
    }

    @Transactional
    public StockTransferDto transfer(Long productId, CreateStockTransferRequest request) {
        Integer qty = request.quantity();
        if (qty == null || qty <= 0) {
            throw new BadRequestException("error.transfer.quantityPositive");
        }
        if (request.fromWarehouseId() == null || request.toWarehouseId() == null) {
            throw new BadRequestException("error.transfer.warehousesRequired");
        }
        if (request.fromWarehouseId().equals(request.toWarehouseId())) {
            throw new BadRequestException("error.transfer.warehousesDifferent");
        }

        Product product = requireProduct(productId);
        // Access is checked on both ends: a user must be allowed to draw from the source and stock the
        // destination (managers always pass; warehouse staff must be assigned to both).
        Warehouse from = warehouseService.requireAccess(request.fromWarehouseId());
        Warehouse to = warehouseService.requireAccess(request.toWarehouseId());

        String lotNumber = null;
        if (request.batchId() != null) {
            ProductBatch batch = productBatchRepository.findById(request.batchId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lot not found with id: " + request.batchId()));
            if (batch.getProduct() == null || !batch.getProduct().getId().equals(productId)) {
                throw new BadRequestException("error.inventory.lotNotInProduct");
            }
            if (batch.getWarehouse() == null || !batch.getWarehouse().getId().equals(from.getId())) {
                throw new BadRequestException("error.transfer.lotNotInSource");
            }
            if (qty > batch.getQuantity()) {
                throw new BadRequestException("error.transfer.lotExceeds",
                        batch.getLotNumber(), batch.getQuantity());
            }
            lotNumber = batch.getLotNumber();

            // Draw the units out of the source lot and re-receive them into the destination warehouse so
            // the lot (with its dates) continues to exist there.
            batch.setQuantity(batch.getQuantity() - qty);
            productBatchRepository.save(batch);
            productBatchService.receiveBatch(product, to, lotNumber, qty,
                    batch.getProductionDate(), batch.getExpiryDate());
        }

        // Move the authoritative per-warehouse totals (source guard throws if it lacks the stock).
        warehouseService.adjustWarehouseStock(from, product, -qty);
        warehouseService.adjustWarehouseStock(to, product, qty);

        StockTransfer transfer = new StockTransfer();
        transfer.setProduct(product);
        transfer.setFromWarehouse(from);
        transfer.setToWarehouse(to);
        transfer.setLotNumber(lotNumber);
        transfer.setQuantity(qty);
        transfer.setNote(request.note() != null && !request.note().isBlank() ? request.note().trim() : null);
        StockTransfer saved = transferRepository.save(transfer);

        return StockTransferDto.from(saved, actorFor(saved.getCreatedById(), new HashMap<>()));
    }

    /** A product's transfer history, newest first. */
    @Transactional(readOnly = true)
    public List<StockTransferDto> historyForProduct(Long productId) {
        requireProduct(productId);
        Map<Long, StockTransferDto.Actor> actorCache = new HashMap<>();
        return transferRepository.findByProductIdWithWarehouses(productId).stream()
                .map(t -> StockTransferDto.from(t, actorFor(t.getCreatedById(), actorCache)))
                .toList();
    }

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));
    }

    private StockTransferDto.Actor actorFor(Long userId, Map<Long, StockTransferDto.Actor> cache) {
        if (userId == null) {
            return null;
        }
        return cache.computeIfAbsent(userId, id -> userRepository.findById(id)
                .map(this::toActor)
                .orElse(null));
    }

    private StockTransferDto.Actor toActor(User user) {
        String name = user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName()
                : user.getEmail();
        return new StockTransferDto.Actor(user.getId(), name);
    }
}
