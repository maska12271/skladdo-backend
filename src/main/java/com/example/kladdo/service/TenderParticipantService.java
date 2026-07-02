package com.example.kladdo.service;

import com.example.kladdo.dto.TenderParticipantRequestDto;
import com.example.kladdo.dto.TenderParticipantResponseDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Tender;
import com.example.kladdo.model.TenderParticipant;
import com.example.kladdo.repository.TenderParticipantRepository;
import com.example.kladdo.repository.TenderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TenderParticipantService {

    private final TenderRepository tenderRepository;
    private final TenderParticipantRepository tenderParticipantRepository;

    public List<TenderParticipantResponseDto> findByTenderId(Long tenderId) {
        return tenderParticipantRepository.findByTenderId(tenderId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public TenderParticipantResponseDto create(Long tenderId, TenderParticipantRequestDto dto) {
        Tender tender = tenderRepository.findById(tenderId)
                .orElseThrow(() -> new RuntimeException("Tender not found"));

        if (Boolean.TRUE.equals(dto.getWinner())) {
            clearWinner(tenderId);
        }

        TenderParticipant participant = new TenderParticipant();
        participant.setTender(tender);
        participant.setManufacturerName(dto.getManufacturerName());
        participant.setOfferedPrice(dto.getOfferedPrice());
        participant.setNotes(dto.getNotes());
        participant.setWinner(Boolean.TRUE.equals(dto.getWinner()));

        return toDto(tenderParticipantRepository.save(participant));
    }

    public TenderParticipantResponseDto update(Long tenderId, Long participantId, TenderParticipantRequestDto dto) {
        TenderParticipant participant = tenderParticipantRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found with id: " + participantId));

        if (!participant.getTender().getId().equals(tenderId)) {
            throw new BadRequestException("error.tender.participantMismatch");
        }

        if (Boolean.TRUE.equals(dto.getWinner())) {
            clearWinner(tenderId);
        }

        participant.setManufacturerName(dto.getManufacturerName());
        participant.setOfferedPrice(dto.getOfferedPrice());
        participant.setNotes(dto.getNotes());
        participant.setWinner(Boolean.TRUE.equals(dto.getWinner()));

        return toDto(tenderParticipantRepository.save(participant));
    }

    public void delete(Long tenderId, Long participantId) {
        TenderParticipant participant = tenderParticipantRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found with id: " + participantId));

        if (!participant.getTender().getId().equals(tenderId)) {
            throw new BadRequestException("error.tender.participantMismatch");
        }

        tenderParticipantRepository.delete(participant);
    }

    private void clearWinner(Long tenderId) {
        List<TenderParticipant> participants = tenderParticipantRepository.findByTenderId(tenderId);
        for (TenderParticipant participant : participants) {
            participant.setWinner(false);
        }
        tenderParticipantRepository.saveAll(participants);
    }

    private TenderParticipantResponseDto toDto(TenderParticipant participant) {
        return new TenderParticipantResponseDto(
                participant.getId(),
                participant.getManufacturerName(),
                participant.getOfferedPrice(),
                participant.getNotes(),
                participant.getWinner()
        );
    }
}