package com.example.skladdo.service;

import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.AuditAction;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.Service;
import com.example.skladdo.model.ServiceCategory;
import com.example.skladdo.model.TaxRate;
import com.example.skladdo.repository.ServiceRepository;
import com.example.skladdo.repository.TaxRateRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD for the {@link Service} catalogue. The stock-free half of {@link ProductService}: no plan cap
 * (services are uncapped), no warehouse or batch cascade on delete, no images.
 *
 * <p>The stereotype is fully qualified because the entity this class manages is itself called
 * {@code Service} - see the note on {@link Service}.</p>
 */
@org.springframework.stereotype.Service
public class ServiceService {

    private final ServiceRepository serviceRepository;
    private final ServiceCategoryService serviceCategoryService;
    private final TaxRateRepository taxRateRepository;
    private final CompanySettingsService companySettingsService;
    private final AuditService auditService;

    public ServiceService(ServiceRepository serviceRepository,
                          ServiceCategoryService serviceCategoryService,
                          TaxRateRepository taxRateRepository,
                          CompanySettingsService companySettingsService,
                          AuditService auditService) {
        this.serviceRepository = serviceRepository;
        this.serviceCategoryService = serviceCategoryService;
        this.taxRateRepository = taxRateRepository;
        this.companySettingsService = companySettingsService;
        this.auditService = auditService;
    }

    /**
     * Paged search for the list page: free-text across name / code, multi-select category, and the
     * active flag. Any argument that is null/empty is simply not applied.
     */
    public Page<Service> search(String search, List<Long> categoryIds, Boolean active, Pageable pageable) {
        Specification<Service> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("code")), like)
                ));
            }
            if (categoryIds != null && !categoryIds.isEmpty()) {
                predicates.add(root.get("category").get("id").in(categoryIds));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return serviceRepository.findAll(specification, pageable);
    }

    public Service findById(Long id) {
        return serviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found with id: " + id));
    }

    @Transactional
    public Service create(Service service) {
        applyNewServiceDefaults(service);
        service.setCode(blankToNull(service.getCode()));
        resolveCategory(service);
        resolveTaxRate(service);

        Service saved = serviceRepository.save(service);
        auditService.record(AuditService.ENTITY_SERVICE, saved.getId(), AuditAction.CREATE, saved.getName());
        return saved;
    }

    @Transactional
    public Service update(Long id, Service updatedService) {
        Service service = findById(id);

        service.setName(updatedService.getName());
        service.setCode(blankToNull(updatedService.getCode()));
        service.setCategory(updatedService.getCategory());
        service.setDescription(updatedService.getDescription());
        service.setPrice(updatedService.getPrice());
        service.setCurrency(resolveCurrency(updatedService.getCurrency(), service.getCurrency()));
        service.setActive(updatedService.getActive());
        service.setTaxRate(updatedService.getTaxRate());
        service.setRecurrenceMonths(updatedService.getRecurrenceMonths());

        resolveCategory(service);
        resolveTaxRate(service);

        Service saved = serviceRepository.save(service);
        auditService.record(AuditService.ENTITY_SERVICE, saved.getId(), AuditAction.UPDATE, saved.getName());
        return saved;
    }

    /**
     * Deletes without checking whether the service is still referenced by an order or a tender
     * requirement: the FK violation that raises is turned into a clean 409 by
     * {@code GlobalExceptionHandler}, the same way every other catalogue delete behaves.
     */
    @Transactional
    public void delete(Long id) {
        Service service = findById(id);
        String name = service.getName();
        serviceRepository.delete(service);
        auditService.record(AuditService.ENTITY_SERVICE, id, AuditAction.DELETE, name);
    }

    /** Fills in the company-configured defaults for fields the caller left blank. */
    private void applyNewServiceDefaults(Service service) {
        CompanySettings settings = companySettingsService.getOrCreate();

        if (service.getTaxRate() == null) {
            taxRateRepository.findFirstByIsDefaultTrue().ifPresent(service::setTaxRate);
        }
        if (service.getCurrency() == null || service.getCurrency().isBlank()) {
            service.setCurrency(settings.getCurrency());
        } else {
            service.setCurrency(service.getCurrency().trim().toUpperCase());
        }
    }

    /**
     * The currency to store on an edited service: the requested code when supplied, otherwise the
     * value already on the service, falling back to the company base currency.
     */
    private String resolveCurrency(String requested, String existing) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase();
        }
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return companySettingsService.getOrCreate().getCurrency();
    }

    /**
     * Replaces a client-supplied category reference with the managed, tenant-scoped entity so a
     * service can never be linked to another company's category. A null category is left as-is -
     * unlike a product, a service need not be categorised.
     */
    private void resolveCategory(Service service) {
        ServiceCategory category = service.getCategory();
        if (category != null && category.getId() != null) {
            service.setCategory(serviceCategoryService.findById(category.getId()));
        } else {
            service.setCategory(null);
        }
    }

    /** Same re-attachment as {@link #resolveCategory}, for the optional tax rate. */
    private void resolveTaxRate(Service service) {
        TaxRate rate = service.getTaxRate();
        if (rate != null && rate.getId() != null) {
            TaxRate managed = taxRateRepository.findById(rate.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Tax rate not found with id: " + rate.getId()));
            service.setTaxRate(managed);
        } else {
            service.setTaxRate(null);
        }
    }

    /** Trims a string and returns null when it is null or blank (so the unique code stores NULL, not ""). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
