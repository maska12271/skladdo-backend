package com.example.skladdo.service;

import com.example.skladdo.model.AuditAction;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.Manufacturer;
import com.example.skladdo.model.PartnerCategory;
import com.example.skladdo.repository.ManufacturerRepository;
import com.example.skladdo.repository.PartnerCategoryRepository;
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

    private final AuditService auditService;

    public ManufacturerService(ManufacturerRepository manufacturerRepository,
                               PartnerCategoryRepository partnerCategoryRepository,
                               AuditService auditService) {
        this.manufacturerRepository = manufacturerRepository;
        this.partnerCategoryRepository = partnerCategoryRepository;
        this.auditService = auditService;
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
        boolean isNew = manufacturer.getId() == null;
        Manufacturer saved = manufacturerRepository.save(manufacturer);
        auditService.record(AuditService.ENTITY_MANUFACTURER, saved.getId(),
                isNew ? AuditAction.CREATE : AuditAction.UPDATE, saved.getName());
        return saved;
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
        Manufacturer saved = manufacturerRepository.save(manufacturer);
        auditService.record(AuditService.ENTITY_MANUFACTURER, saved.getId(), AuditAction.UPDATE, saved.getName());
        return saved;
    }

    public void delete(Long id) {
        Manufacturer manufacturer = findById(id);
        String name = manufacturer.getName();
        manufacturerRepository.delete(manufacturer);
        auditService.record(AuditService.ENTITY_MANUFACTURER, id, AuditAction.DELETE, name);
    }

    /** Countries used by this tenant's manufacturers, most-used first (drives the country picker order). */
    public List<String> getCountriesByUsage() {
        return manufacturerRepository.findCountriesByUsage();
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
