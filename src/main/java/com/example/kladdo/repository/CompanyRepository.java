package com.example.kladdo.repository;

import com.example.kladdo.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByRegistrationCode(String registrationCode);
}
