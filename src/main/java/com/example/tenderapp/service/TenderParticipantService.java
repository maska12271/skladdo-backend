package com.example.tenderapp.service;

import com.example.tenderapp.dto.TenderParticipantRequestDto;
import com.example.tenderapp.dto.TenderParticipantResponseDto;
import com.example.tenderapp.model.Tender;
import com.example.tenderapp.model.TenderParticipant;
import com.example.tenderapp.repository.TenderParticipantRepository;
import com.example.tenderapp.repository.TenderRepository;
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
                .orElseThrow(() -> new RuntimeException("Participant not found"));

        if (!participant.getTender().getId().equals(tenderId)) {
            throw new RuntimeException("Participant does not belong to tender");
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
                .orElseThrow(() -> new RuntimeException("Participant not found"));

        if (!participant.getTender().getId().equals(tenderId)) {
            throw new RuntimeException("Participant does not belong to tender");
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