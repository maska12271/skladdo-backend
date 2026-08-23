package com.example.skladdo.repository;

import com.example.skladdo.model.Company;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByRegistrationCode(String registrationCode);

    /**
     * Sets a company's account type directly in the database. Native because
     * {@link Company#getType()}'s column is mapped {@code updatable = false} - the type is fixed at signup
     * and nothing in the app may change it, which is the point.
     *
     * <p>This is the deliberate exception: repairing a database seeded before the account types existed,
     * where the row is simply wrong and there is no other way to correct it. Not reachable from any
     * endpoint - see {@code DataInitializer.repairWarehouseAccountType}.</p>
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE company SET type = :type WHERE id = :companyId", nativeQuery = true)
    void setTypeDirectly(@Param("companyId") Long companyId, @Param("type") String type);

    /** How many companies signed up through each invite link, for the operator's link list. */
    @Query("select c.inviteLinkId, count(c) from Company c where c.inviteLinkId is not null group by c.inviteLinkId")
    java.util.List<Object[]> countSignupsPerInviteLink();

    long countByInviteLinkId(Long inviteLinkId);

    /** Skladdo's own shell company. There is only ever one; see {@code PlatformAdminBootstrap}. */
    Optional<Company> findFirstByType(com.example.skladdo.model.CompanyType type);
}
