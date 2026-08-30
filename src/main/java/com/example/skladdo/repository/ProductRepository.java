package com.example.skladdo.repository;

import com.example.skladdo.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    long countByManufacturerId(Long manufacturerId);

    /**
     * Products whose stock has fallen below their configured minimum, expressed as a query so the
     * notification job does not have to load every product. Nulls are coalesced to 0 because both columns
     * are nullable on older rows.
     *
     * <p>Narrower than the dashboard's low-stock figure, which also counts empty products that have no
     * minimum set - see {@code DashboardService.needsRestocking}. Alerting on those would mean a
     * notification for every product nobody ever configured a threshold for.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Product p
            where coalesce(p.stockQuantity, 0) < coalesce(p.minimumStock, 0)
            """)
    java.util.List<Product> findLowStock();

    /**
     * Un-files every product in a category, so the category itself can then be deleted.
     *
     * <p>A bulk update rather than loading the products: a category can hold the whole catalogue, and
     * nothing here needs the entities. {@code clearAutomatically} matters because of that - any product
     * already in the persistence context would otherwise keep its stale category and write it back.</p>
     *
     * <p>Not tenant-filtered, and does not need to be: a product can only reference a category belonging
     * to its own company, so matching on the category id cannot reach another tenant's rows.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Product p set p.category = null where p.category.id = :categoryId")
    int clearCategory(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);
}