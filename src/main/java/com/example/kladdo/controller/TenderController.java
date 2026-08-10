package com.example.kladdo.controller;

import com.example.kladdo.dto.TenderAnalyticsDto;
import com.example.kladdo.dto.TenderOptionDto;
import com.example.kladdo.dto.TenderOrdersDto;
import com.example.kladdo.dto.TenderRequestDto;
import com.example.kladdo.dto.TenderResponseDto;
import com.example.kladdo.service.TenderAnalyticsService;
import com.example.kladdo.service.TenderOrdersService;
import com.example.kladdo.service.TenderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/tenders")
@RequiredArgsConstructor
public class TenderController {

    private final TenderService tenderService;
    private final TenderOrdersService tenderOrdersService;
    private final TenderAnalyticsService tenderAnalyticsService;
    private final com.example.kladdo.service.CompanySettingsService settingsService;

    /** Next suggested tender number, without advancing the counter (mirrors the order endpoints). */
    @GetMapping("/next-number")
    @PreAuthorize("@perm.canCreate(authentication, 'TENDERS')")
    public java.util.Map<String, String> nextNumber() {
        return java.util.Map.of("number", settingsService.peekNextTenderNumber());
    }

    /** Lightweight list for the order forms' "part of a tender" picker. */
    @GetMapping("/options")
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public List<TenderOptionDto> options() {
        return tenderService.options();
    }

    /** Aggregated analytics for the tenders dashboard over a {@code publishedAt} window (both bounds optional). */
    @GetMapping("/dashboard")
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public TenderAnalyticsDto dashboard(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return tenderAnalyticsService.getAnalytics(parseDate(from), parseDate(to));
    }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public Page<TenderResponseDto> findAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        return tenderService.findAll(search, status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public TenderResponseDto findById(@PathVariable Long id) {
        return tenderService.findById(id);
    }

    /** Revenue/spending/profit and the linked sales + purchase orders for a tender. */
    @GetMapping("/{id}/orders")
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public TenderOrdersDto orders(@PathVariable Long id) {
        return tenderOrdersService.getForTender(id);
    }

    @PostMapping
    @PreAuthorize("@perm.canCreate(authentication, 'TENDERS')")
    public TenderResponseDto create(@Valid @RequestBody TenderRequestDto dto) {
        return tenderService.create(dto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public TenderResponseDto update(@PathVariable Long id, @Valid @RequestBody TenderRequestDto dto) {
        return tenderService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'TENDERS')")
    public void delete(@PathVariable Long id) {
        tenderService.delete(id);
    }
}