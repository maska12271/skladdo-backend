package com.example.kladdo.repository;

import com.example.kladdo.model.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * The per-tenant "last-used rate" cache: one row per currency (see {@link ExchangeRate}). Looked up when
 * the ECB feed has no rate for a currency, and upserted whenever a transaction is saved with a rate.
 */
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByCurrencyCode(String currencyCode);
}
