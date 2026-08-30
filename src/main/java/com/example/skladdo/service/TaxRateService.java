package com.example.skladdo.service;

import com.example.skladdo.dto.TaxRateDto;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.TaxRate;
import com.example.skladdo.repository.TaxRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * CRUD for the calling company's {@link TaxRate}s. Guarantees that at most one rate is marked as the
 * default at any time: marking a rate default clears the flag on the others, and deleting the default
 * promotes the next available rate so a company is never left without one.
 */
@Service
public class TaxRateService {

    private final TaxRateRepository repository;

    public TaxRateService(TaxRateRepository repository) {
        this.repository = repository;
    }

    /**
     * The Estonian VAT bands, as they stood in 2026: standard 24% (the default), 13% on accommodation, 9%
     * on books, medicines and periodicals, and a 0% band for exports and intra-EU supply.
     *
     * <p>Held here rather than inline in the callers so the demo seeder and real company provisioning
     * cannot drift apart - they did, and the seeder was still handing out the pre-July-2025 22% rate.</p>
     */
    private static final List<DefaultRate> DEFAULT_RATES = List.of(
            new DefaultRate("Standard VAT", new BigDecimal("24"), true),
            new DefaultRate("Accommodation VAT", new BigDecimal("13"), false),
            new DefaultRate("Reduced VAT", new BigDecimal("9"), false),
            new DefaultRate("Zero-rated", new BigDecimal("0"), false));

    private record DefaultRate(String name, BigDecimal percentage, boolean isDefault) {}

    /**
     * Gives a newly created company a starting tax catalogue, so it can price a product and issue an
     * invoice on day one instead of first having to build one from nothing. They are ordinary rows
     * afterwards - free to rename, re-rate or delete.
     *
     * <p>Does nothing when the company already has rates, so it is safe to call more than once and safe to
     * add to a provisioning path that may already have seeded. Requires the target company to be bound as
     * the current tenant: {@link TaxRate} is {@code @TenantId}-scoped, so both the count and the inserts
     * land on whichever company is bound.</p>
     *
     * @return the rate marked default, or {@code null} when the company already had rates
     */
    @Transactional
    public TaxRate seedDefaults() {
        if (repository.count() > 0) {
            return null;
        }
        TaxRate defaultRate = null;
        for (DefaultRate spec : DEFAULT_RATES) {
            TaxRate rate = new TaxRate();
            rate.setName(spec.name());
            rate.setPercentage(spec.percentage());
            rate.setDefault(spec.isDefault());
            rate.setActive(true);
            TaxRate saved = repository.save(rate);
            if (spec.isDefault()) {
                defaultRate = saved;
            }
        }
        return defaultRate;
    }

    @Transactional(readOnly = true)
    public List<TaxRateDto> findAll() {
        return repository.findAllByOrderByNameAsc().stream().map(TaxRateDto::from).toList();
    }

    @Transactional
    public TaxRateDto create(TaxRateDto dto) {
        TaxRate rate = new TaxRate();
        rate.setName(dto.name());
        rate.setPercentage(dto.percentage());
        rate.setActive(dto.activeOrDefault());
        // The very first rate is the default regardless of what the caller asked for.
        boolean makeDefault = dto.isDefaultOrFalse() || repository.count() == 0;
        rate.setDefault(makeDefault);
        TaxRate saved = repository.save(rate);
        if (makeDefault) {
            clearOtherDefaults(saved.getId());
        }
        return TaxRateDto.from(saved);
    }

    @Transactional
    public TaxRateDto update(Long id, TaxRateDto dto) {
        TaxRate rate = require(id);
        rate.setName(dto.name());
        rate.setPercentage(dto.percentage());
        rate.setActive(dto.activeOrDefault());
        rate.setDefault(dto.isDefaultOrFalse());
        TaxRate saved = repository.save(rate);
        if (dto.isDefaultOrFalse()) {
            clearOtherDefaults(saved.getId());
        }
        return TaxRateDto.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        TaxRate rate = require(id);
        boolean wasDefault = rate.isDefault();
        repository.delete(rate);
        if (wasDefault) {
            // Promote any remaining rate so the company keeps a default.
            repository.findAllByOrderByNameAsc().stream().findFirst().ifPresent(next -> {
                next.setDefault(true);
                repository.save(next);
            });
        }
    }

    private void clearOtherDefaults(Long keepId) {
        for (TaxRate other : repository.findAllByOrderByNameAsc()) {
            if (!other.getId().equals(keepId) && other.isDefault()) {
                other.setDefault(false);
                repository.save(other);
            }
        }
    }

    private TaxRate require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tax rate not found with id: " + id));
    }
}
