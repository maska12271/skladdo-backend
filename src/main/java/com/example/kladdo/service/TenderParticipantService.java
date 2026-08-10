package com.example.kladdo.service;

import com.example.kladdo.dto.TenderParticipantRequestDto;
import com.example.kladdo.dto.TenderParticipantResponseDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.TenderParticipant;
import com.example.kladdo.model.TenderPart;
import com.example.kladdo.repository.TenderParticipantRepository;
import com.example.kladdo.repository.TenderPartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages the participants of a single tender <em>part</em>: competitors we track plus our own company's
 * (non-deletable) row. Enforces one winner per part and that a winner is, implicitly, participating.
 */
@Service
@RequiredArgsConstructor
public class TenderParticipantService {

    private final TenderPartRepository tenderPartRepository;
    private final TenderParticipantRepository tenderParticipantRepository;

    @Transactional
    public TenderParticipantResponseDto create(Long tenderId, Long partId, TenderParticipantRequestDto dto) {
        TenderPart part = requirePart(tenderId, partId);
        if (Boolean.TRUE.equals(dto.getWinner())) {
            clearWinner(partId);
        }
        TenderParticipant participant = new TenderParticipant();
        participant.setPart(part);
        participant.setTender(part.getTender());
        participant.setOwnCompany(false);
        apply(participant, dto);
        return toDto(tenderParticipantRepository.save(participant));
    }

    @Transactional
    public TenderParticipantResponseDto update(Long tenderId, Long partId, Long participantId,
                                               TenderParticipantRequestDto dto) {
        TenderParticipant participant = requireParticipant(tenderId, partId, participantId);
        if (Boolean.TRUE.equals(dto.getWinner())) {
            clearWinner(partId);
        }
        apply(participant, dto);
        return toDto(tenderParticipantRepository.save(participant));
    }

    @Transactional
    public void delete(Long tenderId, Long partId, Long participantId) {
        TenderParticipant participant = requireParticipant(tenderId, partId, participantId);
        if (Boolean.TRUE.equals(participant.getOwnCompany())) {
            throw new BadRequestException("error.tender.ownCompanyUndeletable");
        }
        tenderParticipantRepository.delete(participant);
    }

    /** Copies the editable fields. The own-company row keeps its (company) name; a winner is participating. */
    private void apply(TenderParticipant participant, TenderParticipantRequestDto dto) {
        if (!Boolean.TRUE.equals(participant.getOwnCompany())) {
            participant.setManufacturerName(dto.getManufacturerName());
        }
        participant.setOfferedPrice(dto.getOfferedPrice());
        participant.setNotes(dto.getNotes());
        boolean winner = Boolean.TRUE.equals(dto.getWinner());
        participant.setWinner(winner);
        participant.setParticipating(winner || Boolean.TRUE.equals(dto.getParticipating()));
    }

    private void clearWinner(Long partId) {
        List<TenderParticipant> participants = tenderParticipantRepository.findByPartIdOrderByOwnCompanyDescIdAsc(partId);
        for (TenderParticipant p : participants) {
            if (Boolean.TRUE.equals(p.getWinner())) {
                p.setWinner(false);
            }
        }
        tenderParticipantRepository.saveAll(participants);
    }

    private TenderPart requirePart(Long tenderId, Long partId) {
        TenderPart part = tenderPartRepository.findById(partId)
                .orElseThrow(() -> new ResourceNotFoundException("Tender part not found with id: " + partId));
        if (!part.getTender().getId().equals(tenderId)) {
            throw new BadRequestException("error.tender.partMismatch");
        }
        return part;
    }

    private TenderParticipant requireParticipant(Long tenderId, Long partId, Long participantId) {
        requirePart(tenderId, partId);
        TenderParticipant participant = tenderParticipantRepository.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found with id: " + participantId));
        if (participant.getPart() == null || !participant.getPart().getId().equals(partId)) {
            throw new BadRequestException("error.tender.participantMismatch");
        }
        return participant;
    }

    /** Shared mapper (also used by {@code TenderPartService} when embedding participants in a part). */
    static TenderParticipantResponseDto toDto(TenderParticipant p) {
        return new TenderParticipantResponseDto(
                p.getId(),
                p.getManufacturerName(),
                p.getOfferedPrice(),
                p.getNotes(),
                p.getWinner(),
                p.getOwnCompany(),
                p.getParticipating()
        );
    }
}
