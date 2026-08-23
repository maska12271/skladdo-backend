package com.example.skladdo.repository;

import com.example.skladdo.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * The account for an address, ignoring case. This is what every sign-in path uses: an email address
     * identifies the same mailbox whatever its case, so someone typing {@code Name@example.com} must not
     * be told their account does not exist. Addresses are stored lowercased (see the write paths in
     * {@code RegistrationService}/{@code UserService}/{@code AdminService}), so at most one row matches.
     */
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    /**
     * Whether an address is already taken, ignoring case - the guard that keeps the above lookup
     * unambiguous by refusing a second account differing from an existing one only by case.
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Users occupying a plan seat: everyone in the company who is not archived.
     *
     * <p>An archived account cannot sign in or do anything until it is unarchived, so it does not consume a
     * seat - archiving is how you retire someone without destroying the record, and it would be perverse if
     * the safe option were the one that cost you a seat while deleting was the only way to free one.</p>
     *
     * <p>The null check is load-bearing: {@code archived} is a nullable {@link Boolean}, so rows written
     * before the column existed hold {@code null} rather than {@code false}, and {@code archived = false}
     * alone would silently stop counting every one of them.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(u) from User u
            where u.company.id = :companyId and (u.archived is null or u.archived = false)
            """)
    long countSeatsInUse(@org.springframework.data.repository.query.Param("companyId") Long companyId);

    List<User> findByCompanyIdOrderByIdDesc(Long companyId);

    /** Used to fetch a user while ensuring they belong to the caller's company (tenant isolation). */
    Optional<User> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * Stamps a successful sign-in. A direct update rather than a load-and-save because the login path runs
     * in a read-only transaction, and because this is a bookkeeping write that must never interfere with
     * the profile being returned.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update User u set u.lastLoginAt = :at where u.id = :id")
    void recordLogin(@org.springframework.data.repository.query.Param("id") Long id,
                     @org.springframework.data.repository.query.Param("at") java.time.Instant at);

    // --- Platform administration (cross-company; see AdminService) ---------------------------------

    /** Everyone the deployment currently marks as a platform operator. */
    List<User> findByPlatformAdminTrue();

    /**
     * Live accounts belonging to <em>customers</em>, for the admin dashboard's "total users".
     *
     * <p>Operators are excluded: they work for Skladdo, so counting them would report the platform's own
     * staff as customer growth. The null check on the type is load-bearing for the same reason it is
     * everywhere else - companies predating the column read back {@code null}, which means BUSINESS.</p>
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(u) from User u
            where (u.archived is null or u.archived = false)
              and (u.company.type is null or u.company.type <> com.example.skladdo.model.CompanyType.PLATFORM)
            """)
    long countCustomerSeats();

    /** Whether a company still has an operator in it - suspending such a company is refused. */
    boolean existsByCompanyIdAndPlatformAdminTrue(Long companyId);

    List<User> findByCompanyId(Long companyId);

    /** Every company's owner in one query, so the admin list can name a contact without an N+1 lookup. */
    List<User> findByRole(com.example.skladdo.model.Role role);

    /**
     * Companies with at least one sign-in since {@code cutoff}. Returns ids so the caller can count them
     * or intersect them with another set; a company is "active" when anyone in it has signed in.
     */
    @org.springframework.data.jpa.repository.Query("""
            select distinct u.company.id from User u
            where u.lastLoginAt is not null and u.lastLoginAt >= :cutoff
            """)
    List<Long> findCompanyIdsActiveSince(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);

    /** The most recent sign-in in each company, for the admin list's "last active" column. */
    @org.springframework.data.jpa.repository.Query("""
            select u.company.id, max(u.lastLoginAt) from User u
            where u.lastLoginAt is not null group by u.company.id
            """)
    List<Object[]> findLastLoginPerCompany();

    /** Non-archived seat counts for every company at once, so the admin list is not N+1. */
    @org.springframework.data.jpa.repository.Query("""
            select u.company.id, count(u) from User u
            where (u.archived is null or u.archived = false) group by u.company.id
            """)
    List<Object[]> countSeatsPerCompany();
}
