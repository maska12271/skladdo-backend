package com.example.kladdo.service;

import com.example.kladdo.dto.CompanyProfileDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.AuditAction;
import com.example.kladdo.model.Company;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The calling company's own profile. {@link Company} is the tenant root, so it is not {@code @TenantId}
 * scoped - every lookup here is pinned to {@code SecurityUtil.currentCompanyId()} instead, which is what
 * keeps one company from reading or renaming another.
 */
@Service
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final AuditService auditService;

    public CompanyService(CompanyRepository companyRepository, AuditService auditService) {
        this.companyRepository = companyRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public CompanyProfileDto get() {
        return CompanyProfileDto.from(requireCurrent());
    }

    /** Renames the company / updates its registration code. */
    @Transactional
    public CompanyProfileDto update(CompanyProfileDto dto) {
        Company company = requireCurrent();

        String registrationCode = blankToNull(dto.registrationCode());
        // The code is unique system-wide, so a clash with another company has to be reported rather than
        // surfacing as a constraint violation.
        if (registrationCode != null) {
            companyRepository.findByRegistrationCode(registrationCode)
                    .filter(other -> !other.getId().equals(company.getId()))
                    .ifPresent(other -> {
                        throw new BadRequestException("error.company.registrationCodeExists");
                    });
        }

        company.setName(dto.name().trim());
        company.setRegistrationCode(registrationCode);
        // dto.type() is deliberately ignored: the account type is chosen at signup and is a security
        // boundary, so it is reported here but never editable.
        Company saved = companyRepository.save(company);

        auditService.record(AuditService.ENTITY_COMPANY, saved.getId(), AuditAction.UPDATE, saved.getName());
        return CompanyProfileDto.from(saved);
    }

    private Company requireCurrent() {
        Long companyId = SecurityUtil.currentCompanyId();
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
