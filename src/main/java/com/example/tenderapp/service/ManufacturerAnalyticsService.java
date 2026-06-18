package com.example.tenderapp.service;

import com.example.tenderapp.dto.ManufacturerDetailsDto;
import com.example.tenderapp.dto.ManufacturerDetailsDto.OrderLine;
import com.example.tenderapp.exception.ResourceNotFoundException;
import com.example.tenderapp.model.Product;
import com.example.tenderapp.model.PurchaseOrder;
import com.example.tenderapp.model.PurchaseOrderItem;
import com.example.tenderapp.repository.ManufacturerRepository;
import com.example.tenderapp.repository.ProductRepository;
import com.example.tenderapp.repository.PurchaseOrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the {@link ManufacturerDetailsDto} for the manufacturer detail page from the purchase-order
 * lines the manufacturer (supplier) appears in. Read-only and transactional so lazy associations
 * (order -> product) can be traversed with open-in-view disabled.
 */
@Service
public class ManufacturerAnalyticsService {

    private final ManufacturerRepository manufacturerRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;

    public ManufacturerAnalyticsService(ManufacturerRepository manufacturerRepository,
                                        ProductRepository productRepository,
                                        PurchaseOrderItemRepository purchaseOrderItemRepository) {
        this.manufacturerRepository = manufacturerRepository;
        this.productRepository = productRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
    }

    @Transactional(readOnly = true)
    public ManufacturerDetailsDto getDetails(Long manufacturerId) {
        if (!manufacturerRepository.existsById(manufacturerId)) {
            throw new ResourceNotFoundException("Manufacturer not found with id: " + manufacturerId);
        }

        List<PurchaseOrderItem> items = purchaseOrderItemRepository.findByPurchaseOrder_Manufacturer_Id(manufacturerId);
        List<OrderLine> lines = items.stream()
                .map(item -> {
                    PurchaseOrder order = item.getPurchaseOrder();
                    Product product = item.getProduct();
                    return new OrderLine(
                            order != null ? order.getId() : null,
                            order != null ? order.getOrderNumber() : null,
                            order != null ? order.getOrderDate() : null,
                            order != null && order.getStatus() != null ? order.getStatus().name() : null,
                            product != null ? product.getId() : null,
                            product != null ? product.getName() : null,
                            product != null ? product.getSku() : null,
                            nz(item.getQuantity()),
                            nz(item.getLineTotal())
                    );
                })
                .sorted(byDateDesc())
                .toList();

        int catalogProductCount = (int) productRepository.countByManufacturerId(manufacturerId);
        return new ManufacturerDetailsDto(catalogProductCount, lines);
    }

    private static Comparator<OrderLine> byDateDesc() {
        return Comparator.comparing(OrderLine::orderDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static int nz(Integer value) {
        return value != null ? value : 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
