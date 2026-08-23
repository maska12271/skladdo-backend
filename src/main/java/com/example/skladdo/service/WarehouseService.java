package com.example.skladdo.service;

import com.example.skladdo.dto.CreateWarehouseRequest;
import com.example.skladdo.dto.ProductBatchDto;
import com.example.skladdo.dto.ProductWarehouseStockDto;
import com.example.skladdo.dto.UpdateWarehouseRequest;
import com.example.skladdo.dto.WarehouseDto;
import com.example.skladdo.dto.WarehouseStockItemDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ForbiddenException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.ConnectionStatus;
import com.example.skladdo.model.Product;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.User;
import com.example.skladdo.model.Warehouse;
import com.example.skladdo.model.WarehouseConnection;
import com.example.skladdo.model.WarehouseStock;
import com.example.skladdo.repository.ProductBatchRepository;
import com.example.skladdo.repository.ProductRepository;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.repository.WarehouseConnectionRepository;
import com.example.skladdo.repository.WarehouseRepository;
import com.example.skladdo.repository.WarehouseStockRepository;
import com.example.skladdo.security.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final WarehouseStockRepository warehouseStockRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductBatchRepository productBatchRepository;
    private final WarehouseConnectionRepository connectionRepository;

    public WarehouseService(WarehouseRepository warehouseRepository,
                            WarehouseStockRepository warehouseStockRepository,
                            ProductRepository productRepository,
                            UserRepository userRepository,
                            ProductBatchRepository productBatchRepository,
                            WarehouseConnectionRepository connectionRepository) {
        this.warehouseRepository = warehouseRepository;
        this.warehouseStockRepository = warehouseStockRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.productBatchRepository = productBatchRepository;
        this.connectionRepository = connectionRepository;
    }

    // -----------------------------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<WarehouseDto> findAllForCurrentUser() {
        List<Warehouse> warehouses;
        if (SecurityUtil.isPartnerSession()) {
            warehouses = warehouseRepository.findAllById(partnerWarehouseIds());
        } else if (isCurrentUserManager()) {
            warehouses = warehouseRepository.findAll();
        } else {
            warehouses = warehouseRepository.findAssignedToUser(SecurityUtil.currentUserId());
        }
        return warehouses.stream().map(WarehouseDto::from).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseDto findById(Long id) {
        return WarehouseDto.from(requireAccess(id));
    }

    @Transactional
    public WarehouseDto create(CreateWarehouseRequest request) {
        Warehouse warehouse = new Warehouse();
        warehouse.setName(request.name().trim());
        warehouse.setAddress(request.address());
        warehouse.setActive(true);
        return WarehouseDto.from(warehouseRepository.save(warehouse));
    }

    @Transactional
    public WarehouseDto update(Long id, UpdateWarehouseRequest request) {
        Warehouse warehouse = requireById(id);
        warehouse.setName(request.name().trim());
        warehouse.setAddress(request.address());
        if (request.active() != null) {
            warehouse.setActive(request.active());
        }
        return WarehouseDto.from(warehouseRepository.save(warehouse));
    }

    @Transactional
    public void delete(Long id) {
        Warehouse warehouse = requireById(id);
        // Deduct this warehouse's contribution from each product's cached total before removing rows.
        List<WarehouseStock> stockRows = warehouseStockRepository
                .findByWarehouseIdWithProduct(id, Pageable.unpaged()).getContent();
        for (WarehouseStock ws : stockRows) {
            Product product = ws.getProduct();
            product.setStockQuantity(Math.max(0, product.getStockQuantity() - ws.getQuantity()));
            productRepository.save(product);
        }
        warehouseStockRepository.deleteAll(stockRows);
        warehouseRepository.delete(warehouse);
    }

    // -----------------------------------------------------------------------------------------
    // Stock views
    // -----------------------------------------------------------------------------------------

    /** Paginated list of all products and their stock level inside a single warehouse. */
    @Transactional(readOnly = true)
    public Page<WarehouseStockItemDto> getStockForWarehouse(Long warehouseId, Pageable pageable) {
        requireAccess(warehouseId);
        return warehouseStockRepository.findByWarehouseIdWithProduct(warehouseId, pageable)
                .map(ws -> new WarehouseStockItemDto(
                        ws.getProduct().getId(),
                        ws.getProduct().getName(),
                        ws.getProduct().getSku(),
                        ws.getQuantity(),
                        ws.getProduct().getMinimumStock() != null ? ws.getProduct().getMinimumStock() : 0,
                        ws.getProduct().getPrice(),
                        ws.getProduct().getCurrency()
                ));
    }

    /**
     * Map of {@code productId -> on-hand quantity} for one warehouse, for the order forms to show how
     * much of each product the chosen warehouse holds (and warn before overselling). Quantities only -
     * no prices - so it is readable by anyone who can build an order (same reach as the warehouse list).
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> getStockLevels(Long warehouseId) {
        requireAccess(warehouseId);
        Map<Long, Integer> levels = new HashMap<>();
        for (WarehouseStock ws : warehouseStockRepository
                .findByWarehouseIdWithProduct(warehouseId, Pageable.unpaged()).getContent()) {
            levels.put(ws.getProduct().getId(), ws.getQuantity());
        }
        return levels;
    }

    /** Per-warehouse stock breakdown for a single product (all warehouses, visible to current user). */
    @Transactional(readOnly = true)
    public List<ProductWarehouseStockDto> getWarehouseStockForProduct(Long productId) {
        return warehouseStockRepository.findByProductIdWithWarehouse(productId).stream()
                .filter(ws -> isCurrentUserManager() || isUserAssignedToWarehouse(ws.getWarehouse().getId()))
                .map(ws -> new ProductWarehouseStockDto(
                        ws.getWarehouse().getId(),
                        ws.getWarehouse().getName(),
                        ws.getQuantity()
                ))
                .toList();
    }

    /** In-stock lots of a product (across warehouses the current user can see) for the lot breakdown. */
    @Transactional(readOnly = true)
    public List<ProductBatchDto> getBatchesForProduct(Long productId) {
        return productBatchRepository.findInStockByProductId(productId).stream()
                .filter(b -> isCurrentUserManager() || isUserAssignedToWarehouse(b.getWarehouse().getId()))
                .map(ProductBatchDto::from)
                .toList();
    }

    // -----------------------------------------------------------------------------------------
    // Stock mutation (called by InventoryService and order services)
    // -----------------------------------------------------------------------------------------

    /**
     * Applies a signed stock delta to the given warehouse+product pair, then syncs
     * {@link Product#getStockQuantity()} to reflect the new company-wide total.
     */
    @Transactional
    public void adjustWarehouseStock(Warehouse warehouse, Product product, int delta) {
        WarehouseStock stock = warehouseStockRepository
                .findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                .orElseGet(() -> {
                    WarehouseStock s = new WarehouseStock();
                    s.setWarehouse(warehouse);
                    s.setProduct(product);
                    s.setQuantity(0);
                    return s;
                });

        int newQty = stock.getQuantity() + delta;
        if (newQty < 0) {
            throw new BadRequestException("error.warehouse.insufficientStock",
                    warehouse.getName(), product.getName(), stock.getQuantity(), -delta);
        }
        stock.setQuantity(newQty);
        warehouseStockRepository.save(stock);

        // Keep the denormalized total on the product in sync.
        int total = warehouseStockRepository.sumQuantityByProductId(product.getId());
        product.setStockQuantity(total);
        productRepository.save(product);
    }

    // -----------------------------------------------------------------------------------------
    // Warehouse access helpers (used by other services)
    // -----------------------------------------------------------------------------------------

    /**
     * Loads a warehouse and validates that the current user can access it.
     * Managers always pass; restricted users must be assigned.
     */
    @Transactional(readOnly = true)
    public Warehouse requireAccess(Long warehouseId) {
        Warehouse warehouse = requireById(warehouseId);
        if (!isCurrentUserManager() && !isUserAssignedToWarehouse(warehouseId)) {
            throw new ForbiddenException("error.warehouse.noAccess");
        }
        return warehouse;
    }

    /**
     * Returns the IDs of warehouses the current user may access, or {@code null} when the user
     * is a manager (meaning no warehouse filter should be applied).
     */
    @Transactional(readOnly = true)
    public List<Long> getAccessibleWarehouseIds() {
        if (SecurityUtil.isPartnerSession()) {
            return partnerWarehouseIds();
        }
        if (isCurrentUserManager()) {
            return null; // managers see everything
        }
        return warehouseRepository.findAssignedToUser(SecurityUtil.currentUserId())
                .stream().map(Warehouse::getId).toList();
    }

    // -----------------------------------------------------------------------------------------
    // User warehouse assignment
    // -----------------------------------------------------------------------------------------

    @Transactional
    public List<Long> setUserWarehouses(Long userId, List<Long> warehouseIds) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        Set<Warehouse> assigned = new HashSet<>();
        if (warehouseIds != null) {
            for (Long wId : warehouseIds) {
                assigned.add(requireById(wId));
            }
        }
        user.setWarehouses(assigned);
        userRepository.save(user);
        return assigned.stream().map(Warehouse::getId).sorted().toList();
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private Warehouse requireById(Long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with id: " + id));
    }

    private boolean isCurrentUserManager() {
        Role role = SecurityUtil.currentUser().getRole();
        return role == Role.OWNER || role == Role.ADMINISTRATOR;
    }

    private boolean isUserAssignedToWarehouse(Long warehouseId) {
        if (SecurityUtil.isPartnerSession()) {
            return partnerWarehouseIds().contains(warehouseId);
        }
        Long userId = SecurityUtil.currentUserId();
        return warehouseRepository.findAssignedToUser(userId)
                .stream().anyMatch(w -> w.getId().equals(warehouseId));
    }

    /**
     * The warehouses a visiting partner may touch inside this client company: exactly the ones the client
     * assigned to the connection, and nothing else in the company.
     *
     * <p>Derived from the connection rather than from {@code user_warehouse} rows on purpose. Rows would
     * have to be written for every one of the partner's staff when a connection starts and cleaned up when
     * it ends or when they hire someone - and any gap in that bookkeeping is either a lockout or, far
     * worse, lingering access to a company that already disconnected. Reading the connection cannot drift,
     * and an empty assignment correctly grants nothing.</p>
     */
    private List<Long> partnerWarehouseIds() {
        return connectionRepository
                .findFirstByWarehouseCompanyIdAndClientCompanyIdAndStatus(
                        SecurityUtil.currentHomeCompanyId(),
                        SecurityUtil.currentCompanyId(),
                        ConnectionStatus.ACTIVE)
                .map(connection -> List.copyOf(connection.getWarehouseIds()))
                .orElseGet(List::of);
    }
}
