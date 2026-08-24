package com.example.skladdo.controller;

import com.example.skladdo.dto.ServiceDetailsDto;
import com.example.skladdo.model.Service;
import com.example.skladdo.service.ServiceAnalyticsService;
import com.example.skladdo.service.ServiceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The service catalogue. Deliberately has none of {@link ProductController}'s inventory
 * sub-resources - a service has no stock to adjust, no lots to receive and nowhere to transfer to.
 */
@RestController
@RequestMapping("/api/services")
@Tag(name = "Services")
public class ServiceController {

    private final ServiceService serviceService;
    private final ServiceAnalyticsService serviceAnalyticsService;

    public ServiceController(ServiceService serviceService,
                             ServiceAnalyticsService serviceAnalyticsService) {
        this.serviceService = serviceService;
        this.serviceAnalyticsService = serviceAnalyticsService;
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'SERVICES')")
    public Page<Service> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<Long> categoryId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return serviceService.search(search, categoryId, active, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canReadReference(authentication, 'SERVICES')")
    public Service getById(@PathVariable Long id) {
        return serviceService.findById(id);
    }

    @GetMapping("/{id}/details")
    @PreAuthorize("@perm.canReadReference(authentication, 'SERVICES')")
    public ServiceDetailsDto getDetails(@PathVariable Long id) {
        return serviceAnalyticsService.getDetails(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'SERVICES')")
    public Service create(@Valid @RequestBody Service service) {
        return serviceService.create(service);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'SERVICES')")
    public Service update(@PathVariable Long id, @Valid @RequestBody Service service) {
        return serviceService.update(id, service);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'SERVICES')")
    public void delete(@PathVariable Long id) {
        serviceService.delete(id);
    }
}
