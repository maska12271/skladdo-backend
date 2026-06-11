package com.example.tenderapp.controller;

import com.example.tenderapp.model.Manufacturer;
import com.example.tenderapp.service.ManufacturerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manufacturers")
@Tag(name = "Manufacturers")
public class ManufacturerController {

    private final ManufacturerService manufacturerService;

    public ManufacturerController(ManufacturerService manufacturerService) {
        this.manufacturerService = manufacturerService;
    }

    @GetMapping
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
    public Manufacturer getById(@PathVariable Long id) {
        return manufacturerService.findById(id);
    }

    @PostMapping
    public Manufacturer create(@Valid @RequestBody Manufacturer manufacturer) {
        return manufacturerService.save(manufacturer);
    }

    @PutMapping("/{id}")
    public Manufacturer update(@PathVariable Long id, @Valid @RequestBody Manufacturer manufacturer) {
        return manufacturerService.update(id, manufacturer);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        manufacturerService.delete(id);
    }
}