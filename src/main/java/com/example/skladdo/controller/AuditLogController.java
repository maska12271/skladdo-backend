package com.example.skladdo.controller;

import com.example.skladdo.dto.AuditLogDto;
import com.example.skladdo.model.AuditAction;
import com.example.skladdo.service.AuditService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The company-wide audit trail. Owner/administrator only - it exposes every user's activity, so it is
 * gated by role rather than by a per-module permission (same as users and settings).
 */
@RestController
@RequestMapping("/api/audit-logs")
@Tag(name = "Audit log")
@PreAuthorize("hasAnyRole('OWNER', 'ADMINISTRATOR')")
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public Page<AuditLogDto> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> entityType,
            @RequestParam(required = false) List<AuditAction> action,
            @RequestParam(required = false) List<Long> actorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return auditService.search(search, entityType, action, actorId, pageable);
    }

    /** Distinct actors in the trail, so the viewer can offer a "who" filter without a users lookup. */
    @GetMapping("/actors")
    public List<AuditService.ActorOption> actors() {
        return auditService.actors();
    }
}
