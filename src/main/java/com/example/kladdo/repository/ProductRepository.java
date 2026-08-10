package com.example.kladdo.repository;

import com.example.kladdo.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    long countByManufacturerId(Long manufacturerId);

    /**
     * Products whose stock has fallen below their configured minimum - the same "low stock" rule the
     * dashboard applies, expressed as a query so the notification job does not have to load every product.
     * Nulls are coalesced to 0 because both columns are nullable on older rows.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Product p
            where coalesce(p.stockQuantity, 0) < coalesce(p.minimumStock, 0)
            """)
    java.util.List<Product> findLowStock();
}