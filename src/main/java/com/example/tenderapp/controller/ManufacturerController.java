package com.example.tenderapp.controller;

import com.example.tenderapp.dto.ManufacturerDetailsDto;
import com.example.tenderapp.model.Manufacturer;
import com.example.tenderapp.service.ManufacturerAnalyticsService;
import com.example.tenderapp.service.ManufacturerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manufacturers")
@Tag(name = "Manufacturers")
public class ManufacturerController {

    private final ManufacturerService manufacturerService;
    private final ManufacturerAnalyticsService manufacturerAnalyticsService;

    public ManufacturerController(ManufacturerService manufacturerService,
                                  ManufacturerAnalyticsService manufacturerAnalyticsService) {
        this.manufacturerService = manufacturerService;
        this.manufacturerAnalyticsService = manufacturerAnalyticsService;
    }

    @GetMapping
    @PreAuthorize("@perm.canReadReference(authentication, 'MANUFACTURERS')")
    public Page<Manufacturer> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return manufacturerService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canReadReference(authentication, 'MANUFACTURERS')")
    public Manufacturer getById(@PathVariable Long id) {
        return manufacturerService.findById(id);
    }

    @GetMapping("/{id}/details")
    @PreAuthorize("@perm.canReadReference(authentication, 'MANUFACTURERS')")
    public ManufacturerDetailsDto getDetails(@PathVariable Long id) {
        return manufacturerAnalyticsService.getDetails(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'MANUFACTURERS')")
    public Manufacturer create(@Valid @RequestBody Manufacturer manufacturer) {
        return manufacturerService.save(manufacturer);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'MANUFACTURERS')")
    public Manufacturer update(@PathVariable Long id, @Valid @RequestBody Manufacturer manufacturer) {
        return manufacturerService.update(id, manufacturer);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'MANUFACTURERS')")
    public void delete(@PathVariable Long id) {
        manufacturerService.delete(id);
    }
}