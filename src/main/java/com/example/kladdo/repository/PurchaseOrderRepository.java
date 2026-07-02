package com.example.kladdo.repository;

import com.example.kladdo.model.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long>, JpaSpecificationExecutor<PurchaseOrder> {

    /** Purchase orders authored by a given user (company-scoped automatically via the tenant filter). */
    List<PurchaseOrder> findByCreatedById(Long createdById);
}