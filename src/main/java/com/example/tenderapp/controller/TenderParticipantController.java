package com.example.tenderapp.controller;

import com.example.tenderapp.dto.TenderParticipantRequestDto;
import com.example.tenderapp.dto.TenderParticipantResponseDto;
import com.example.tenderapp.service.TenderParticipantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tenders/{tenderId}/participants")
@RequiredArgsConstructor
public class TenderParticipantController {

    private final TenderParticipantService tenderParticipantService;

    @GetMapping
    public List<TenderParticipantResponseDto> findByTenderId(@PathVariable Long tenderId) {
        return tenderParticipantService.findByTenderId(tenderId);
    }

    @PostMapping
    public TenderParticipantResponseDto create(
            @PathVariable Long tenderId,
            @Valid @RequestBody TenderParticipantRequestDto dto
    ) {
        return tenderParticipantService.create(tenderId, dto);
    }

    @PutMapping("/{participantId}")
    public TenderParticipantResponseDto update(
            @PathVariable Long tenderId,
            @PathVariable Long participantId,
            @Valid @RequestBody TenderParticipantRequestDto dto
    ) {
        return tenderParticipantService.update(tenderId, participantId, dto);
    }

    @DeleteMapping("/{participantId}")
    public void delete(
            @PathVariable Long tenderId,
            @PathVariable Long participantId
    ) {
        tenderParticipantService.delete(tenderId, participantId);
    }
}