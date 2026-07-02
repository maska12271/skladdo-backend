package com.example.kladdo.repository;

import com.example.kladdo.model.CompanySettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanySettingsRepository extends JpaRepository<CompanySettings, Long> {

    /**
     * The current company's settings row. The {@code @TenantId} filter scopes the query to the
     * caller's company, so at most one row is ever returned.
     */
    Optional<CompanySettings> findFirstByOrderByIdAsc();
}
