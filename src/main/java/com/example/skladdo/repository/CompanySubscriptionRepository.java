package com.example.skladdo.repository;

import com.example.skladdo.model.CompanySubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanySubscriptionRepository extends JpaRepository<CompanySubscription, Long> {

    /** The calling company's single subscription row (the {@code @TenantId} filter scopes it). */
    Optional<CompanySubscription> findFirstByOrderByIdAsc();

    /**
     * Every company's subscription, for the platform admin panel - one row per company, as
     * {@code [companyId, plan, status, currentPeriodStart, currentPeriodEnd, cancelAtPeriodEnd]}.
     *
     * <p>Native on purpose. {@link CompanySubscription} is {@code @TenantId}-scoped and the discriminator
     * is fixed when the Hibernate session opens, so a JPQL query here would silently return whichever
     * single tenant happened to be bound - the operator would see one company's plan reported for all of
     * them. A native query bypasses the filter instead.</p>
     *
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT COMPANY_ID, PLAN, STATUS,
                   CURRENT_PERIOD_START, CURRENT_PERIOD_END, CANCEL_AT_PERIOD_END
            FROM COMPANY_SUBSCRIPTION
            """, nativeQuery = true)
    java.util.List<Object[]> findAllIgnoringTenant();
}
