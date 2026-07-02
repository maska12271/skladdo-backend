package com.example.kladdo.service;

import com.example.kladdo.model.Product;
import com.example.kladdo.model.ProductBatch;
import com.example.kladdo.model.SalesOrderItem;
import com.example.kladdo.model.SalesOrderItemBatch;
import com.example.kladdo.model.Warehouse;
import com.example.kladdo.model.WarehouseMethod;
import com.example.kladdo.repository.ProductBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Owns lot-level (batch) stock: receiving lots from purchase orders and drawing them down with the
 * product's configured {@link WarehouseMethod} when sales ship. {@code WarehouseStock} still tracks
 * the authoritative per-warehouse totals (managed by {@code WarehouseService}); this service keeps
 * the lot breakdown beneath those totals in sync at the same trigger points.
 */
@Service
public class ProductBatchService {

    private final ProductBatchRepository batchRepository;

    public ProductBatchService(ProductBatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    // ---------------------------------------------------------------------------------------------
    // Receiving (purchase orders)
    // ---------------------------------------------------------------------------------------------

    /**
     * Records receipt of {@code quantity} units of a lot into a warehouse. Tops up the matching
     * existing lot if present, otherwise creates it. No-op when no lot number was supplied (legacy /
     * untracked lines) so existing flat-stock flows keep working.
     */
    @Transactional
    public void receiveBatch(Product product, Warehouse warehouse, String lotNumber, int quantity,
                             LocalDate productionDate, LocalDate expiryDate) {
        if (lotNumber == null || lotNumber.isBlank() || quantity <= 0) {
            return;
        }
        String lot = lotNumber.trim();
        ProductBatch batch = batchRepository
                .findByProductIdAndWarehouseIdAndLotNumber(product.getId(), warehouse.getId(), lot)
                .orElseGet(() -> {
                    ProductBatch b = new ProductBatch();
                    b.setProduct(product);
                    b.setWarehouse(warehouse);
                    b.setLotNumber(lot);
                    b.setQuantity(0);
                    b.setOriginalQuantity(0);
                    b.setProductionDate(productionDate);
                    b.setExpiryDate(expiryDate);
                    return b;
                });
        batch.setQuantity(batch.getQuantity() + quantity);
        batch.setOriginalQuantity(nz(batch.getOriginalQuantity()) + quantity);
        // Fill in dates if a prior receipt of this lot left them blank.
        if (batch.getProductionDate() == null) batch.setProductionDate(productionDate);
        if (batch.getExpiryDate() == null) batch.setExpiryDate(expiryDate);
        batchRepository.save(batch);
    }

    /**
     * Reverses a receipt (purchase order un-shipped or deleted): removes {@code quantity} units from
     * the lot. Best-effort — never drives a lot negative.
     */
    @Transactional
    public void reverseReceipt(Product product, Warehouse warehouse, String lotNumber, int quantity) {
        if (lotNumber == null || lotNumber.isBlank() || quantity <= 0) {
            return;
        }
        batchRepository
                .findByProductIdAndWarehouseIdAndLotNumber(product.getId(), warehouse.getId(), lotNumber.trim())
                .ifPresent(batch -> {
                    batch.setQuantity(Math.max(0, batch.getQuantity() - quantity));
                    batch.setOriginalQuantity(Math.max(batch.getQuantity(), nz(batch.getOriginalQuantity()) - quantity));
                    batchRepository.save(batch);
                });
    }

    // ---------------------------------------------------------------------------------------------
    // Spending (sales orders)
    // ---------------------------------------------------------------------------------------------

    /**
     * Allocates {@code quantity} units of a product out of a warehouse using the product's method,
     * splitting across lots as needed. Decrements lot quantities and records each consumed lot on the
     * sales line. Allocates as much as the lots hold; any shortfall (legacy stock that predates lot
     * tracking) is left untracked — the hard stock-sufficiency check lives in
     * {@code WarehouseService.adjustWarehouseStock}.
     */
    @Transactional
    public void allocateForSale(SalesOrderItem item, Product product, Warehouse warehouse, int quantity) {
        if (quantity <= 0) {
            return;
        }
        WarehouseMethod method = product.getWarehouseMethod() == null ? WarehouseMethod.FEFO : product.getWarehouseMethod();
        List<ProductBatch> batches = switch (method) {
            case FEFO -> batchRepository.findAvailableFefo(product.getId(), warehouse.getId());
            case FIFO -> batchRepository.findAvailableFifo(product.getId(), warehouse.getId());
            case LIFO -> batchRepository.findAvailableLifo(product.getId(), warehouse.getId());
        };

        int remaining = quantity;
        for (ProductBatch batch : batches) {
            if (remaining <= 0) break;
            int take = Math.min(remaining, batch.getQuantity());
            if (take <= 0) continue;

            batch.setQuantity(batch.getQuantity() - take);
            batchRepository.save(batch);

            SalesOrderItemBatch alloc = new SalesOrderItemBatch();
            alloc.setSalesOrderItem(item);
            alloc.setProductBatch(batch);
            alloc.setLotNumber(batch.getLotNumber());
            alloc.setProductionDate(batch.getProductionDate());
            alloc.setExpiryDate(batch.getExpiryDate());
            alloc.setQuantityUsed(take);
            item.getBatchAllocations().add(alloc);

            remaining -= take;
        }
    }

    /**
     * Reverses a sale (order un-shipped or deleted): returns each recorded allocation's quantity to
     * its lot, then clears the line's allocations (orphan-removed on save).
     */
    @Transactional
    public void reverseSale(SalesOrderItem item) {
        for (SalesOrderItemBatch alloc : item.getBatchAllocations()) {
            ProductBatch batch = alloc.getProductBatch();
            if (batch != null && alloc.getQuantityUsed() != null) {
                batch.setQuantity(batch.getQuantity() + alloc.getQuantityUsed());
                batchRepository.save(batch);
            }
        }
        item.getBatchAllocations().clear();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
