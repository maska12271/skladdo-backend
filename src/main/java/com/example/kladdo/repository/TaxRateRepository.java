package com.example.kladdo.repository;

import com.example.kladdo.model.TaxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

    List<TaxRate> findAllByOrderByNameAsc();

    Optional<TaxRate> findFirstByIsDefaultTrue();
}
