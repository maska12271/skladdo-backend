package com.example.kladdo.repository;

import com.example.kladdo.model.StockTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    /** A product's transfer history, newest first, with both warehouses joined for display. */
    @Query("SELECT st FROM StockTransfer st " +
           "JOIN FETCH st.fromWarehouse JOIN FETCH st.toWarehouse " +
           "WHERE st.product.id = :productId ORDER BY st.createdAt DESC, st.id DESC")
    List<StockTransfer> findByProductIdWithWarehouses(@Param("productId") Long productId);
}
