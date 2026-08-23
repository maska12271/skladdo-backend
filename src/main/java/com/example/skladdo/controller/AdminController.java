package com.example.skladdo.controller;

import com.example.skladdo.dto.AdminCompanyDetailDto;
import com.example.skladdo.dto.AdminCompanyDto;
import com.example.skladdo.dto.AdminStatsDto;
import com.example.skladdo.dto.CreateCompanyRequest;
import com.example.skladdo.dto.CreateInviteLinkRequest;
import com.example.skladdo.dto.CreatedCompanyResponse;
import com.example.skladdo.dto.InviteLinkDto;
import com.example.skladdo.dto.SetSponsorshipRequest;
import com.example.skladdo.repository.StoredFileRepository;
import com.example.skladdo.service.AdminService;
import com.example.skladdo.service.InviteLinkService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The platform operator's API: figures across every tenant, and the actions that manage a company from
 * outside it.
 *
 * <p>Gated on {@code ROLE_PLATFORM_ADMIN}, which is granted only by the {@code app.platform-admin-emails}
 * property at startup (see {@code PlatformAdminBootstrap}). That authority is deliberately separate from
 * the company roles every other controller uses: a company owner is the most privileged person
 * <em>inside</em> their tenant and must still get a 403 here.</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Platform administration")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final InviteLinkService inviteLinkService;
    private final StoredFileRepository storedFiles;

    public AdminController(AdminService adminService, InviteLinkService inviteLinkService,
                           StoredFileRepository storedFiles) {
        this.adminService = adminService;
        this.inviteLinkService = inviteLinkService;
        this.storedFiles = storedFiles;
    }

    /** How much object storage each company is using: {@code companyId -> {bytes, files}}. */
    public record StorageUsageDto(Long companyId, long bytes, long files) {}

    /**
     * Object storage used per company.
     *
     * <p>Summed from {@code STORED_FILE} rather than by listing the bucket: the keys carry no tenant, so a
     * listing cannot be attributed to anyone. A company with no uploads since the table was introduced
     * simply has no row and is reported as zero - objects predating it were never recorded.</p>
     */
    @GetMapping("/storage-usage")
    public List<StorageUsageDto> storageUsage() {
        return storedFiles.sumSizeByCompanyIgnoringTenant().stream()
                .map(row -> new StorageUsageDto(
                        row[0] == null ? null : ((Number) row[0]).longValue(),
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    /** Headline figures for the operator dashboard. */
    @GetMapping("/stats")
    public AdminStatsDto stats() {
        return adminService.stats();
    }

    /**
     * Every company, filtered and paged. {@code status} takes the derived state (ACTIVE / SUSPENDED /
     * OVERDUE) rather than the raw subscription status, since that is what the list column shows.
     */
    @GetMapping("/companies")
    public Page<AdminCompanyDto> companies(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> plan,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return adminService.listCompanies(search, type, status, plan, pageable);
    }

    @GetMapping("/companies/{id}")
    public AdminCompanyDetailDto company(@PathVariable Long id) {
        return adminService.companyDetail(id);
    }

    /**
     * Provisions a company and its owner by hand. The owner receives a link to set their own password -
     * this endpoint never accepts or returns a password.
     */
    @PostMapping("/companies")
    public CreatedCompanyResponse createCompany(@Valid @RequestBody CreateCompanyRequest request) {
        return adminService.createCompany(request);
    }

    /**
     * Grants or extends a company's free period. Extends from the current end date unless
     * {@code fromToday} is set (or the period has already lapsed).
     */
    @PostMapping("/companies/{id}/sponsorship")
    public AdminCompanyDto grantSponsorship(@PathVariable Long id,
                                            @Valid @RequestBody SetSponsorshipRequest request) {
        return adminService.setSponsorship(id, request);
    }

    /** Ends a company's free period now. Its own endpoint so it cannot happen by mistyping a number. */
    @DeleteMapping("/companies/{id}/sponsorship")
    public AdminCompanyDto endSponsorship(@PathVariable Long id) {
        return adminService.clearSponsorship(id);
    }

    // --- Invite links ---------------------------------------------------------------------------

    @GetMapping("/invite-links")
    public List<InviteLinkDto> inviteLinks() {
        return inviteLinkService.list();
    }

    @PostMapping("/invite-links")
    public InviteLinkDto createInviteLink(@Valid @RequestBody CreateInviteLinkRequest request) {
        return inviteLinkService.create(request);
    }

    /** Stops a link working. It is kept, not deleted, so companies keep pointing at where they came from. */
    @PostMapping("/invite-links/{id}/revoke")
    public InviteLinkDto revokeInviteLink(@PathVariable Long id) {
        return inviteLinkService.revoke(id);
    }

    /** Suspends a company: every account in it stops authenticating on its next request. */
    @PostMapping("/companies/{id}/suspend")
    public AdminCompanyDto suspend(@PathVariable Long id) {
        return adminService.setSuspended(id, true);
    }

    /** Lifts a suspension. */
    @PostMapping("/companies/{id}/activate")
    public AdminCompanyDto activate(@PathVariable Long id) {
        return adminService.setSuspended(id, false);
    }
}
