package com.example.kladdo.service;

import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Manufacturer;
import com.example.kladdo.model.PartnerCategory;
import com.example.kladdo.repository.ManufacturerRepository;
import com.example.kladdo.repository.PartnerCategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class ManufacturerService {

    private final ManufacturerRepository manufacturerRepository;
    private final PartnerCategoryRepository partnerCategoryRepository;

    public ManufacturerService(ManufacturerRepository manufacturerRepository,
                               PartnerCategoryRepository partnerCategoryRepository) {
        this.manufacturerRepository = manufacturerRepository;
        this.partnerCategoryRepository = partnerCategoryRepository;
    }

    public Page<Manufacturer> findAll(Pageable pageable) {
        return manufacturerRepository.findAll(pageable);
    }

    public Manufacturer findById(Long id) {
        return manufacturerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturer not found with id: " + id));
    }

    public Manufacturer save(Manufacturer manufacturer) {
        manufacturer.setCategories(resolveCategories(manufacturer.getCategories()));
        return manufacturerRepository.save(manufacturer);
    }

    public Manufacturer update(Long id, Manufacturer updatedManufacturer) {
        Manufacturer manufacturer = findById(id);
        manufacturer.setName(updatedManufacturer.getName());
        manufacturer.setCountry(updatedManufacturer.getCountry());
        manufacturer.setAddress(updatedManufacturer.getAddress());
        manufacturer.setEmail(updatedManufacturer.getEmail());
        manufacturer.setPhone(updatedManufacturer.getPhone());
        manufacturer.setWebsite(updatedManufacturer.getWebsite());
        manufacturer.setNotes(updatedManufacturer.getNotes());
        manufacturer.setCategories(resolveCategories(updatedManufacturer.getCategories()));
        manufacturer.setActive(updatedManufacturer.getActive());
        return manufacturerRepository.save(manufacturer);
    }

    public void delete(Long id) {
        manufacturerRepository.delete(findById(id));
    }

    /**
     * Replaces the loosely-bound categories from the request body (each carries only an id) with the
     * managed entities, validating that every referenced category exists.
     */
    private Set<PartnerCategory> resolveCategories(Set<PartnerCategory> incoming) {
        Set<PartnerCategory> resolved = new LinkedHashSet<>();
        if (incoming == null) {
            return resolved;
        }
        for (PartnerCategory category : incoming) {
            if (category == null || category.getId() == null) {
                continue;
            }
            resolved.add(partnerCategoryRepository.findById(category.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Partner category not found with id: " + category.getId())));
        }
        return resolved;
    }
}
