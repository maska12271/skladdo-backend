package com.example.skladdo.controller;

import com.example.skladdo.dto.TenderParticipantRequestDto;
import com.example.skladdo.dto.TenderParticipantResponseDto;
import com.example.skladdo.service.TenderParticipantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Participants of a tender part - competitors and our own (non-deletable) company row. Reads come embedded
 * in the part; this exposes create/update/delete. Marking a participant winner is done through update.
 */
@RestController
@RequestMapping("/api/tenders/{tenderId}/parts/{partId}/participants")
@RequiredArgsConstructor
public class TenderParticipantController {

    private final TenderParticipantService tenderParticipantService;

    @PostMapping
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public TenderParticipantResponseDto create(@PathVariable Long tenderId, @PathVariable Long partId,
                                               @Valid @RequestBody TenderParticipantRequestDto dto) {
        return tenderParticipantService.create(tenderId, partId, dto);
    }

    @PutMapping("/{participantId}")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public TenderParticipantResponseDto update(@PathVariable Long tenderId, @PathVariable Long partId,
                                               @PathVariable Long participantId,
                                               @Valid @RequestBody TenderParticipantRequestDto dto) {
        return tenderParticipantService.update(tenderId, partId, participantId, dto);
    }

    @DeleteMapping("/{participantId}")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public void delete(@PathVariable Long tenderId, @PathVariable Long partId, @PathVariable Long participantId) {
        tenderParticipantService.delete(tenderId, partId, participantId);
    }
}
