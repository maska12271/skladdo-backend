package com.example.kladdo.repository;

import com.example.kladdo.model.OrderStatusChange;
import com.example.kladdo.model.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusChangeRepository extends JpaRepository<OrderStatusChange, Long> {

    List<OrderStatusChange> findByOrderTypeAndOrderIdOrderByChangedAtAscIdAsc(OrderType orderType, Long orderId);

    void deleteByOrderTypeAndOrderId(OrderType orderType, Long orderId);
}
