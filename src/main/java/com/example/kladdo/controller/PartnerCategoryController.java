package com.example.kladdo.controller;

import com.example.kladdo.model.PartnerCategory;
import com.example.kladdo.service.PartnerCategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/partner-categories")
@Tag(name = "Partner Categories")
public class PartnerCategoryController {

    private final PartnerCategoryService partnerCategoryService;

    public PartnerCategoryController(PartnerCategoryService partnerCategoryService) {
        this.partnerCategoryService = partnerCategoryService;
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'PARTNER_CATEGORIES')")
    public Page<PartnerCategory> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return partnerCategoryService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canReadReference(authentication, 'PARTNER_CATEGORIES')")
    public PartnerCategory getById(@PathVariable Long id) {
        return partnerCategoryService.findById(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'PARTNER_CATEGORIES')")
    public PartnerCategory create(@Valid @RequestBody PartnerCategory partnerCategory) {
        return partnerCategoryService.save(partnerCategory);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'PARTNER_CATEGORIES')")
    public PartnerCategory update(@PathVariable Long id, @Valid @RequestBody PartnerCategory partnerCategory) {
        return partnerCategoryService.update(id, partnerCategory);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'PARTNER_CATEGORIES')")
    public void delete(@PathVariable Long id) {
        partnerCategoryService.delete(id);
    }
}
