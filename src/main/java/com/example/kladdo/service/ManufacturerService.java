package com.example.kladdo.service;

import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Manufacturer;
import com.example.kladdo.model.PartnerCategory;
import com.example.kladdo.repository.ManufacturerRepository;
import com.example.kladdo.repository.PartnerCategoryRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * Paged manufacturer search. Free-text matches name / country / email / phone; filters by active
     * flag and by partner category (a manufacturer matches if it has any of the selected categories).
     */
    public Page<Manufacturer> findAll(String search, List<Long> categoryIds, Boolean active, Pageable pageable) {
        Specification<Manufacturer> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("country")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("phone")), like)
                ));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (categoryIds != null && !categoryIds.isEmpty()) {
                // Many-to-many join can duplicate rows, so collapse them (also keeps the page count right).
                query.distinct(true);
                predicates.add(root.join("categories").get("id").in(categoryIds));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return manufacturerRepository.findAll(specification, pageable);
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
