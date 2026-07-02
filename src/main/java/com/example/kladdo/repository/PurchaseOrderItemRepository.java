package com.example.kladdo.repository;

import com.example.kladdo.model.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByProductId(Long productId);

    List<PurchaseOrderItem> findByPurchaseOrder_Manufacturer_Id(Long manufacturerId);
}