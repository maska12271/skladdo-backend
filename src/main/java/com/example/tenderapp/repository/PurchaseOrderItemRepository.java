package com.example.tenderapp.repository;

import com.example.tenderapp.model.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByProductId(Long productId);

    List<PurchaseOrderItem> findByPurchaseOrder_Manufacturer_Id(Long manufacturerId);
}