package com.example.tenderapp.repository;

import com.example.tenderapp.model.SalesOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, Long> {

    List<SalesOrderItem> findByProductId(Long productId);

    List<SalesOrderItem> findBySalesOrder_Client_Id(Long clientId);

    List<SalesOrderItem> findBySalesOrder_OrderDateGreaterThanEqual(LocalDate date);
}