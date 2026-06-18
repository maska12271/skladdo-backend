package com.example.tenderapp.controller;

import com.example.tenderapp.dto.TenderParticipantRequestDto;
import com.example.tenderapp.dto.TenderParticipantResponseDto;
import com.example.tenderapp.service.TenderParticipantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tenders/{tenderId}/participants")
@RequiredArgsConstructor
public class TenderParticipantController {

    private final TenderParticipantService tenderParticipantService;

    @GetMapping
    @PreAuthorize("@perm.canView(authentication, 'TENDERS')")
    public List<TenderParticipantResponseDto> findByTenderId(@PathVariable Long tenderId) {
        return tenderParticipantService.findByTenderId(tenderId);
    }

    @PostMapping
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public TenderParticipantResponseDto create(
            @PathVariable Long tenderId,
            @Valid @RequestBody TenderParticipantRequestDto dto
    ) {
        return tenderParticipantService.create(tenderId, dto);
    }

    @PutMapping("/{participantId}")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public TenderParticipantResponseDto update(
            @PathVariable Long tenderId,
            @PathVariable Long participantId,
            @Valid @RequestBody TenderParticipantRequestDto dto
    ) {
        return tenderParticipantService.update(tenderId, participantId, dto);
    }

    @DeleteMapping("/{participantId}")
    @PreAuthorize("@perm.canEdit(authentication, 'TENDERS')")
    public void delete(
            @PathVariable Long tenderId,
            @PathVariable Long participantId
    ) {
        tenderParticipantService.delete(tenderId, participantId);
    }
}