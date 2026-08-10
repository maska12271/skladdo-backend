package com.example.kladdo.repository;

import com.example.kladdo.model.CompanySettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CompanySettingsRepository extends JpaRepository<CompanySettings, Long> {

    /**
     * The current company's settings row. The {@code @TenantId} filter scopes the query to the
     * caller's company, so at most one row is ever returned.
     */
    Optional<CompanySettings> findFirstByOrderByIdAsc();

    /**
     * A specific company's settings row, ignoring the {@code @TenantId} filter. Native SQL is not subject
     * to Hibernate's tenant discriminator, so this resolves the right row even outside a request/tenant
     * context (used by the public "forgot password" flow, which has no company bound). Callers must supply
     * a trusted company id.
     */
    @Query(value = "SELECT * FROM company_settings WHERE company_id = :companyId LIMIT 1", nativeQuery = true)
    Optional<CompanySettings> findByCompanyIdIgnoringTenant(@Param("companyId") Long companyId);
}
