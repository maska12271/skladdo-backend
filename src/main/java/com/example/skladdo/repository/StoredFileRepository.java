package com.example.skladdo.repository;

import com.example.skladdo.model.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    Optional<StoredFile> findByStorageKey(String storageKey);

    /**
     * Bytes and object count per company, across every tenant.
     *
     * <p>Native and tenant-ignoring on purpose: this feeds the platform admin panel, whose whole job is to
     * look across companies, and {@code @TenantId} would otherwise narrow it to whichever company the
     * operator's own session is bound to. Same pattern as
     * {@code CompanySubscriptionRepository.findAllIgnoringTenant}.</p>
     *
     * @return rows of {@code [companyId, totalBytes, fileCount]}
     */
    @Query(value = """
            SELECT COMPANY_ID, COALESCE(SUM(SIZE_BYTES), 0), COUNT(*)
            FROM STORED_FILE
            GROUP BY COMPANY_ID
            """, nativeQuery = true)
    List<Object[]> sumSizeByCompanyIgnoringTenant();

    /** The same figures for one company, so the detail page does not have to scan every tenant. */
    @Query(value = """
            SELECT COALESCE(SUM(SIZE_BYTES), 0), COUNT(*)
            FROM STORED_FILE
            WHERE COMPANY_ID = :companyId
            """, nativeQuery = true)
    List<Object[]> sumSizeForCompanyIgnoringTenant(@Param("companyId") Long companyId);
}
