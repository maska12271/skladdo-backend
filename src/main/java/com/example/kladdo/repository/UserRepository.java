package com.example.kladdo.repository;

import com.example.kladdo.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByCompanyId(Long companyId);

    List<User> findByCompanyIdOrderByIdDesc(Long companyId);

    /** Used to fetch a user while ensuring they belong to the caller's company (tenant isolation). */
    Optional<User> findByIdAndCompanyId(Long id, Long companyId);
}
