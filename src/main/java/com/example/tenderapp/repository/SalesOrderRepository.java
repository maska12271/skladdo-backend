package com.example.tenderapp.repository;

import com.example.tenderapp.model.SalesOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long>, JpaSpecificationExecutor<SalesOrder> {

    /** Sales orders authored by a given user (company-scoped automatically via the tenant filter). */
    List<SalesOrder> findByCreatedById(Long createdById);
}