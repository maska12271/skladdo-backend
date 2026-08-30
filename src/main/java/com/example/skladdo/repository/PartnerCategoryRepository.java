package com.example.skladdo.repository;

import com.example.skladdo.model.PartnerCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerCategoryRepository extends JpaRepository<PartnerCategory, Long> {

    /**
     * Removes a partner category from every manufacturer tagged with it, so the category can be deleted.
     *
     * <p>Native, because the rows live in the {@code manufacturer_category} join table, which has no
     * entity to write JPQL against. Safe without a tenant filter for the same reason as
     * {@code ProductRepository.clearCategory}: only this company's manufacturers can reference this
     * company's category.</p>
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            value = "delete from manufacturer_category where category_id = :categoryId", nativeQuery = true)
    int detachFromManufacturers(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);
}
