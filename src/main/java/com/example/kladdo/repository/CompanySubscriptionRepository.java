package com.example.kladdo.repository;

import com.example.kladdo.model.CompanySubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanySubscriptionRepository extends JpaRepository<CompanySubscription, Long> {

    /** The calling company's single subscription row (the {@code @TenantId} filter scopes it). */
    Optional<CompanySubscription> findFirstByOrderByIdAsc();
}
