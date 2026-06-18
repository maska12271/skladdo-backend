package com.example.tenderapp.controller;

import com.example.tenderapp.dto.TenderRequestDto;
import com.example.tenderapp.dto.TenderResponseDto;
import com.example.tenderapp.service.TenderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenders")
@RequiredArgsConstructor
public class TenderController {

    private final TenderService tenderService;

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public Page<TenderResponseDto> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        return tenderService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public TenderResponseDto findById(@PathVariable Long id) {
        return tenderService.findById(id);
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