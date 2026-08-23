package com.example.skladdo.repository;

import com.example.skladdo.model.ConnectionCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link ConnectionCode} carries no {@code @TenantId} (it is written by one company and redeemed by
 * another), so every method here states which side it queries for. The lookup by code is the redeem path
 * and is the one place a caller reaches a row belonging to a company that is not theirs - it is safe
 * because the code itself is the secret.
 */
public interface ConnectionCodeRepository extends JpaRepository<ConnectionCode, Long> {

    Optional<ConnectionCode> findByCode(String code);

    boolean existsByCode(String code);

    /** The issuing company's outstanding codes, newest first - at most one is ever current. */
    List<ConnectionCode> findByClientCompanyIdAndRedeemedAtIsNullOrderByIdDesc(Long clientCompanyId);
}
