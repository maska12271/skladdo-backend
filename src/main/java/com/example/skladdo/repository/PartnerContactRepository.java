package com.example.skladdo.repository;

import com.example.skladdo.model.PartnerContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped through {@link PartnerContact}'s {@code @TenantId}, so no company argument is needed. */
public interface PartnerContactRepository extends JpaRepository<PartnerContact, Long> {

    List<PartnerContact> findByClientIdOrderByNameAsc(Long clientId);

    List<PartnerContact> findByManufacturerIdOrderByNameAsc(Long manufacturerId);

    Optional<PartnerContact> findByIdAndClientId(Long id, Long clientId);

    Optional<PartnerContact> findByIdAndManufacturerId(Long id, Long manufacturerId);

    /**
     * Removes every contact at a partner being deleted.
     *
     * <p>Explicitly {@code @Transactional}: a derived {@code deleteBy…} loads the rows and removes them
     * one by one, which needs a transaction — inherited CRUD methods get one, methods declared here do
     * not. Same annotation, same reason, as {@code PasswordResetTokenRepository.deleteByUserId}. Callers
     * still open their own transaction so the contacts and the partner go together or not at all.</p>
     */
    @Modifying
    @Transactional
    void deleteByClientId(Long clientId);

    /** See {@link #deleteByClientId}. */
    @Modifying
    @Transactional
    void deleteByManufacturerId(Long manufacturerId);
}
