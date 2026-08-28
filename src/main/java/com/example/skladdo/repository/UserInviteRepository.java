package com.example.skladdo.repository;

import com.example.skladdo.model.UserInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link UserInvite} is not {@code @TenantId}-scoped (the redemption has no tenant to scope by), so every
 * administrator-facing method here takes the company explicitly. {@link #findByToken} is the one lookup
 * that deliberately does not - it is how the company is discovered in the first place.
 */
public interface UserInviteRepository extends JpaRepository<UserInvite, Long> {

    Optional<UserInvite> findByToken(String token);

    List<UserInvite> findByCompanyIdOrderByIdDesc(Long companyId);

    Optional<UserInvite> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * Claims an invitation for redemption: stamps it accepted only if it is still usable, and reports
     * whether it was this caller who did so.
     *
     * <p>One statement rather than read-then-write because the redemption runs outside any spanning
     * transaction (see {@code UserInviteService}), so two people opening the same link at the same moment
     * would both pass a separate check. "One link, one account" has to be settled by the database, and a
     * conditional UPDATE settles it wherever the transaction boundaries happen to fall.</p>
     *
     * @return 1 if the invitation was claimed here, 0 if it was already spent, withdrawn or expired
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            update UserInvite i set i.acceptedAt = :now
            where i.id = :id
              and i.acceptedAt is null
              and i.revoked = false
              and i.expiresAt > :now
            """)
    int claim(@Param("id") Long id, @Param("now") Instant now);
}
