package com.example.kladdo.service;

import com.example.kladdo.dto.TenderRequestDto;
import com.example.kladdo.dto.TenderResponseDto;
import com.example.kladdo.model.Client;
import com.example.kladdo.model.Tender;
import com.example.kladdo.repository.ClientRepository;
import com.example.kladdo.repository.TenderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class TenderService {

    private final TenderRepository tenderRepository;
    private final ClientRepository clientRepository;

    @Transactional(readOnly = true)
    public Page<TenderResponseDto> findAll(Pageable pageable) {
        return tenderRepository.findAll(pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public TenderResponseDto findById(Long id) {
        Tender tender = tenderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tender not found"));
        return toDto(tender);
    }

    @Transactional
    public TenderResponseDto create(TenderRequestDto dto) {
        Tender tender = new Tender();
        apply(tender, dto);
        return toDto(tenderRepository.save(tender));
    }

    @Transactional
    public TenderResponseDto update(Long id, TenderRequestDto dto) {
        Tender tender = tenderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tender not found"));
        apply(tender, dto);
        return toDto(tenderRepository.save(tender));
    }

    @Transactional
    public void delete(Long id) {
        tenderRepository.deleteById(id);
    }

    private void apply(Tender tender, TenderRequestDto dto) {
        Client client = clientRepository.findById(dto.getClientId())
                .orElseThrow(() -> new RuntimeException("Client not found"));

        tender.setTitle(dto.getTitle());
        tender.setTenderNumber(dto.getTenderNumber());
        tender.setCustomerName(dto.getCustomerName());
        tender.setClient(client);
        tender.setStatus(dto.getStatus() != null && !dto.getStatus().isBlank()
                ? dto.getStatus()
                : null);
        tender.setPublishedAt(dto.getPublishedAt() != null && !dto.getPublishedAt().isBlank()
                ? LocalDate.parse(dto.getPublishedAt())
                : null);
        tender.setDeadline(dto.getDeadline() != null && !dto.getDeadline().isBlank()
                ? LocalDate.parse(dto.getDeadline())
                : null);
        tender.setDescription(dto.getDescription());
        tender.setEstimatedValue(dto.getEstimatedValue());
    }

    private TenderResponseDto toDto(Tender tender) {
        return new TenderResponseDto(
                tender.getId(),
                tender.getTitle(),
                tender.getTenderNumber(),
                tender.getCustomerName(),
                tender.getClient() != null ? tender.getClient().getId() : null,
                tender.getClient() != null ? tender.getClient().getName() : null,
                tender.getStatus(),
                tender.getPublishedAt() != null ? tender.getPublishedAt().toString() : null,
                tender.getDeadline() != null ? tender.getDeadline().toString() : null,
                tender.getDescription(),
                tender.getEstimatedValue()
        );
    }
}