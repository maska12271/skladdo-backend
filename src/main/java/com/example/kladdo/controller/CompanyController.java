package com.example.kladdo.controller;

import com.example.kladdo.dto.CompanyProfileDto;
import com.example.kladdo.service.CompanyService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The calling company's own profile. Owner/administrator only, like the settings page it is edited from.
 */
@RestController
@RequestMapping("/api/company")
@Tag(name = "Company")
@PreAuthorize("hasAnyRole('OWNER', 'ADMINISTRATOR')")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    public CompanyProfileDto get() {
        return companyService.get();
    }

    @PutMapping
    public CompanyProfileDto update(@Valid @RequestBody CompanyProfileDto request) {
        return companyService.update(request);
    }
}
