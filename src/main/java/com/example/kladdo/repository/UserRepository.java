package com.example.kladdo.repository;

import com.example.kladdo.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

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
}
