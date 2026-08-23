package com.example.skladdo.controller;

import com.example.skladdo.dto.TenderPartRequestDto;
import com.example.skladdo.dto.TenderPartResponseDto;
import com.example.skladdo.service.TenderPartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The lots/parts of a tender, and their reordering. A part carries its requirements (created/updated with
 * the part itself); participants are managed under a part (see {@link TenderParticipantController}).
 */
@RestController
@RequestMapping("/api/tenders/{tenderId}/parts")
@RequiredArgsConstructor
public class TenderPartController {

    private final TenderPartService tenderPartService;

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public List<TenderPartResponseDto> list(@PathVariable Long tenderId) {
        return tenderPartService.listParts(tenderId);
    }

    @PostMapping
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public TenderPartResponseDto create(@PathVariable Long tenderId, @Valid @RequestBody TenderPartRequestDto dto) {
        return tenderPartService.createPart(tenderId, dto);
    }

    @PutMapping("/reorder")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public List<TenderPartResponseDto> reorder(@PathVariable Long tenderId, @RequestBody List<Long> partIds) {
        return tenderPartService.reorder(tenderId, partIds);
    }

    @PutMapping("/{partId}")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public TenderPartResponseDto update(@PathVariable Long tenderId, @PathVariable Long partId,
                                        @Valid @RequestBody TenderPartRequestDto dto) {
        return tenderPartService.updatePart(tenderId, partId, dto);
    }

    @DeleteMapping("/{partId}")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public void delete(@PathVariable Long tenderId, @PathVariable Long partId) {
        tenderPartService.deletePart(tenderId, partId);
    }
}
