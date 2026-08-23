package com.example.skladdo.service;

import com.example.skladdo.dto.TaxRateDto;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.TaxRate;
import com.example.skladdo.repository.TaxRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
