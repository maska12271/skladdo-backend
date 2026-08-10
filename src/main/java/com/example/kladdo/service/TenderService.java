package com.example.kladdo.service;

import com.example.kladdo.model.AuditAction;
import com.example.kladdo.dto.TenderOptionDto;
import com.example.kladdo.dto.TenderRequestDto;
import com.example.kladdo.dto.TenderResponseDto;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Client;
import com.example.kladdo.model.Tender;
import com.example.kladdo.repository.ClientRepository;
import com.example.kladdo.repository.TenderRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TenderService {

    private final TenderRepository tenderRepository;
    private final ClientRepository clientRepository;
    private final CompanySettingsService companySettingsService;
    private final ExchangeRateService exchangeRateService;
    private final TenderPartService tenderPartService;
    private final PlanService planService;
    private final AuditService auditService;

    /**
     * Paged tender search. Free-text matches title / tender number / customer name / linked client
     * name; the status filter narrows to the selected statuses.
     */
    // Writable (not read-only): toDto lazily migrates legacy tenders to the parts model on first access.
    @Transactional
    public Page<TenderResponseDto> findAll(String search, List<String> statuses, Pageable pageable) {
        Specification<Tender> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("tenderNumber")), like),
                        cb.like(cb.lower(root.join("client", JoinType.LEFT).get("name")), like)
                ));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return tenderRepository.findAll(specification, pageable).map(this::toDto);
    }

    @Transactional
    public TenderResponseDto findById(Long id) {
        return toDto(requireTender(id));
    }

    /**
     * Loads a tender in the caller's company or fails with a 404. The lookup is tenant-scoped, so another
     * company's tender is indistinguishable from one that does not exist - which is the intent.
     */
    private Tender requireTender(Long id) {
        return tenderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tender not found with id: " + id));
    }

    /** Lightweight list (id/title/number) for the "part of a tender" picker on the order forms. */
    @Transactional(readOnly = true)
    public List<TenderOptionDto> options() {
        return tenderRepository.findAll().stream()
                .sorted(Comparator.comparing(Tender::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(t -> new TenderOptionDto(t.getId(), t.getTitle(), t.getTenderNumber()))
                .toList();
    }

    @Transactional
    public TenderResponseDto create(TenderRequestDto dto) {
        planService.assertTendersEnabled();
        Tender tender = new Tender();
        apply(tender, dto);
        // A blank number means "use the system-allocated one" - mirrors the order/invoice numbering. Done
        // here rather than in apply() so editing a tender and clearing its number never burns a new one.
        if (tender.getTenderNumber() == null || tender.getTenderNumber().isBlank()) {
            tender.setTenderNumber(companySettingsService.allocateNextTenderNumber());
        }
        Tender saved = tenderRepository.save(tender);
        auditService.record(AuditService.ENTITY_TENDER, saved.getId(), AuditAction.CREATE,
                tenderLabel(saved));
        return toDto(saved);
    }

    /** Language-neutral identifier for the audit trail: the tender number, falling back to its title. */
    private static String tenderLabel(Tender tender) {
        return tender.getTenderNumber() != null && !tender.getTenderNumber().isBlank()
                ? tender.getTenderNumber() : tender.getTitle();
    }

    @Transactional
    public TenderResponseDto update(Long id, TenderRequestDto dto) {
        Tender tender = requireTender(id);
        apply(tender, dto);
        Tender saved = tenderRepository.save(tender);
        auditService.record(AuditService.ENTITY_TENDER, saved.getId(), AuditAction.UPDATE, tenderLabel(saved));
        return toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        // Load first, so a missing (or another company's) id is a 404 instead of a silent no-op that still
        // writes a DELETE row for something that was never deleted.
        Tender tender = requireTender(id);
        String label = tenderLabel(tender);
        tenderRepository.delete(tender);
        auditService.record(AuditService.ENTITY_TENDER, id, AuditAction.DELETE, label);
    }

    private void apply(Tender tender, TenderRequestDto dto) {
        Client client = clientRepository.findById(dto.getClientId())
                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + dto.getClientId()));

        tender.setTitle(dto.getTitle());
        tender.setTenderNumber(dto.getTenderNumber());
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
        tender.setCurrency(resolveCurrency(dto.getCurrency(), tender.getCurrency()));
        tender.setExchangeRate(resolveExchangeRate(dto.getExchangeRate(), tender.getCurrency(), tender.getExchangeRate()));
        exchangeRateService.recordUsedRate(tender.getCurrency(), tender.getExchangeRate());
    }

    /**
     * The currency to store on the tender: the requested code when supplied, otherwise the value already
     * on the tender (on edit), falling back to the company base currency (on create).
     */
    private String resolveCurrency(String requested, String existing) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase();
        }
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return companySettingsService.getOrCreate().getCurrency();
    }

    /**
     * The rate to snapshot on the tender (1 base = rate {@code resolvedCurrency}): 1 when the tender is in
     * the base currency, otherwise the supplied rate, falling back to the rate already on the tender.
     */
    private BigDecimal resolveExchangeRate(BigDecimal requested, String resolvedCurrency, BigDecimal existing) {
        if (resolvedCurrency != null && resolvedCurrency.equalsIgnoreCase(companySettingsService.getOrCreate().getCurrency())) {
            return BigDecimal.ONE;
        }
        if (requested != null && requested.signum() > 0) {
            return requested;
        }
        return existing;
    }

    private TenderResponseDto toDto(Tender tender) {
        // Lazily bring pre-parts tenders onto the parts model, then summarise our participation.
        tenderPartService.ensureParts(tender);
        TenderPartService.Rollup r = tenderPartService.rollup(tender.getId());
        return new TenderResponseDto(
                tender.getId(),
                tender.getTitle(),
                tender.getTenderNumber(),
                tender.getClient() != null ? tender.getClient().getId() : null,
                tender.getClient() != null ? tender.getClient().getName() : null,
                tender.getStatus(),
                tender.getPublishedAt() != null ? tender.getPublishedAt().toString() : null,
                tender.getDeadline() != null ? tender.getDeadline().toString() : null,
                tender.getDescription(),
                tender.getEstimatedValue(),
                tender.getCurrency(),
                tender.getExchangeRate(),
                r.partCount(),
                r.participating(),
                r.won(),
                r.lost(),
                r.pending(),
                r.participatingValue()
        );
    }
}