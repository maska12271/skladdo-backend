package com.example.kladdo.repository;

import com.example.kladdo.model.OrderStatusChange;
import com.example.kladdo.model.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusChangeRepository extends JpaRepository<OrderStatusChange, Long> {

    List<OrderStatusChange> findByOrderTypeAndOrderIdOrderByChangedAtAscIdAsc(OrderType orderType, Long orderId);

    /**
     * Status history for several orders at once, oldest first. Used by the stock ledger, which replays
     * every order's transitions and would otherwise issue one query per order.
     */
    List<OrderStatusChange> findByOrderTypeAndOrderIdInOrderByChangedAtAscIdAsc(OrderType orderType,
                                                                                java.util.Collection<Long> orderIds);

    void deleteByOrderTypeAndOrderId(OrderType orderType, Long orderId);
}
