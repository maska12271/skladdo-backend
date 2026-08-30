package com.example.skladdo.repository;

import com.example.skladdo.model.Service;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ServiceRepository extends JpaRepository<Service, Long>, JpaSpecificationExecutor<Service> {

    /**
     * Un-files every service in a category, so the category itself can then be deleted. See
     * {@code ProductRepository.clearCategory} for why this is a bulk update and why it needs no tenant
     * filter.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Service s set s.category = null where s.category.id = :categoryId")
    int clearCategory(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);
}
