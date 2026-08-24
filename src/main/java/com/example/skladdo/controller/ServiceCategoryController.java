package com.example.skladdo.controller;

import com.example.skladdo.model.ServiceCategory;
import com.example.skladdo.service.ServiceCategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/service-categories")
@Tag(name = "Service categories")
public class ServiceCategoryController {

    private final ServiceCategoryService serviceCategoryService;

    public ServiceCategoryController(ServiceCategoryService serviceCategoryService) {
        this.serviceCategoryService = serviceCategoryService;
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'SERVICE_CATEGORIES')")
    public Page<ServiceCategory> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return serviceCategoryService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canReadReference(authentication, 'SERVICE_CATEGORIES')")
    public ServiceCategory getById(@PathVariable Long id) {
        return serviceCategoryService.findById(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'SERVICE_CATEGORIES')")
    public ServiceCategory create(@Valid @RequestBody ServiceCategory category) {
        return serviceCategoryService.save(category);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'SERVICE_CATEGORIES')")
    public ServiceCategory update(@PathVariable Long id, @Valid @RequestBody ServiceCategory category) {
        return serviceCategoryService.update(id, category);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'SERVICE_CATEGORIES')")
    public void delete(@PathVariable Long id) {
        serviceCategoryService.delete(id);
    }
}
