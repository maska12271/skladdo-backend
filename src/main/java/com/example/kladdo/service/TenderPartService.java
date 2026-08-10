package com.example.kladdo.service;

import com.example.kladdo.dto.TenderPartRequestDto;
import com.example.kladdo.dto.TenderPartResponseDto;
import com.example.kladdo.dto.TenderRequirementRequestDto;
import com.example.kladdo.dto.TenderRequirementResponseDto;
import com.example.kladdo.dto.TenderParticipantResponseDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Company;
import com.example.kladdo.model.Tender;
import com.example.kladdo.model.TenderParticipant;
import com.example.kladdo.model.TenderPart;
import com.example.kladdo.model.TenderRequirement;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.TenderParticipantRepository;
import com.example.kladdo.repository.TenderPartRepository;
import com.example.kladdo.repository.TenderRepository;
import com.example.kladdo.repository.TenderRequirementRepository;
import com.example.kladdo.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages a tender's parts (lots) and keeps the invariant that every tender has at least one part, each
 * carrying a non-deletable "our company" participant row. Legacy tenders (created before parts existed) are
 * migrated lazily by {@link #ensureParts}: a default part is created, the tender's old participants are
 * moved onto it, and the own-company row is seeded. Also computes the per-tender rollup for the list.
 */
@Service
public class TenderPartService {

    private final TenderRepository tenderRepository;
    private final TenderPartRepository tenderPartRepository;
    private final TenderParticipantRepository tenderParticipantRepository;
    private final TenderRequirementRepository tenderRequirementRepository;
    private final CompanyRepository companyRepository;

    public TenderPartService(TenderRepository tenderRepository,
                             TenderPartRepository tenderPartRepository,
                             TenderParticipantRepository tenderParticipantRepository,
                             TenderRequirementRepository tenderRequirementRepository,
                             CompanyRepository companyRepository) {
        this.tenderRepository = tenderRepository;
        this.tenderPartRepository = tenderPartRepository;
        this.tenderParticipantRepository = tenderParticipantRepository;
        this.tenderRequirementRepository = tenderRequirementRepository;
        this.companyRepository = companyRepository;
    }

    /** Per-tender summary of our participation across its parts. */
    public record Rollup(int partCount, int participating, int won, int lost, int pending, double participatingValue) {
    }

    /** The facts about one part that the rollup counting needs (see {@link #computeRollup}). */
    public record PartSummary(Double estimatedValue, boolean weParticipate, boolean weWon, boolean anyWinner) {
    }

    /**
     * Pure rollup counting, split out so it can be unit-tested without a database. A part we won counts as
     * won; a part awarded to someone else counts as lost only if we took part; an undecided part we take
     * part in is pending; and the value of parts we take part in is summed.
     */
    static Rollup computeRollup(List<PartSummary> summaries) {
        int participating = 0, won = 0, lost = 0, pending = 0;
        double value = 0;
        for (PartSummary s : summaries) {
            if (s.weParticipate()) {
                participating++;
                value += s.estimatedValue() != null ? s.estimatedValue() : 0.0;
            }
            if (s.weWon()) {
                won++;
            } else if (s.anyWinner()) {
                if (s.weParticipate()) lost++;
            } else if (s.weParticipate()) {
                pending++;
            }
        }
        return new Rollup(summaries.size(), participating, won, lost, pending, value);
    }

    @Transactional
    public List<TenderPartResponseDto> listParts(Long tenderId) {
        Tender tender = requireTender(tenderId);
        ensureParts(tender);
        return tenderPartRepository.findByTenderIdOrderBySortOrderAscIdAsc(tenderId).stream().map(this::toDto).toList();
    }

    @Transactional
    public TenderPartResponseDto createPart(Long tenderId, TenderPartRequestDto dto) {
        Tender tender = requireTender(tenderId);
        ensureParts(tender); // migrate any legacy tender first so numbering/order stay consistent

        TenderPart part = new TenderPart();
        part.setTender(tender);
        applyPart(part, dto);
        long count = tenderPartRepository.countByTenderId(tenderId);
        part.setSortOrder((int) count);
        if (part.getPartNumber() == null || part.getPartNumber().isBlank()) {
            part.setPartNumber(String.valueOf(count + 1));
        }
        TenderPart saved = tenderPartRepository.save(part);
        seedOwnCompany(saved);
        return toDto(saved);
    }

    @Transactional
    public TenderPartResponseDto updatePart(Long tenderId, Long partId, TenderPartRequestDto dto) {
        TenderPart part = requirePart(tenderId, partId);
        applyPart(part, dto);
        return toDto(tenderPartRepository.save(part));
    }

    @Transactional
    public void deletePart(Long tenderId, Long partId) {
        TenderPart part = requirePart(tenderId, partId);
        if (tenderPartRepository.countByTenderId(tenderId) <= 1) {
            throw new BadRequestException("error.tender.lastPart");
        }
        tenderPartRepository.delete(part);
    }

    @Transactional
    public List<TenderPartResponseDto> reorder(Long tenderId, List<Long> orderedPartIds) {
        requireTender(tenderId);
        List<TenderPart> parts = tenderPartRepository.findByTenderIdOrderBySortOrderAscIdAsc(tenderId);
        int order = 0;
        for (Long id : orderedPartIds) {
            for (TenderPart part : parts) {
                if (part.getId().equals(id)) {
                    part.setSortOrder(order++);
                    break;
                }
            }
        }
        tenderPartRepository.saveAll(parts);
        return tenderPartRepository.findByTenderIdOrderBySortOrderAscIdAsc(tenderId).stream().map(this::toDto).toList();
    }

    /**
     * Guarantees the tender has at least one part. For a tender with none, creates a default part (copying
     * the tender's title/estimated value), moves any pre-parts participants onto it, and seeds the
     * own-company row. Idempotent - a no-op once parts exist.
     */
    @Transactional
    public void ensureParts(Tender tender) {
        if (tenderPartRepository.countByTenderId(tender.getId()) > 0) {
            return;
        }
        TenderPart part = new TenderPart();
        part.setTender(tender);
        part.setPartNumber("1");
        part.setTitle(tender.getTitle());
        part.setEstimatedValue(tender.getEstimatedValue());
        part.setSortOrder(0);
        TenderPart saved = tenderPartRepository.save(part);

        List<TenderParticipant> legacy = tenderParticipantRepository.findByTenderIdAndPartIsNull(tender.getId());
        for (TenderParticipant p : legacy) {
            p.setPart(saved);
            if (p.getParticipating() == null) {
                p.setParticipating(true);
            }
            if (p.getOwnCompany() == null) {
                p.setOwnCompany(false);
            }
        }
        tenderParticipantRepository.saveAll(legacy);

        seedOwnCompany(saved);
    }

    @Transactional(readOnly = true)
    public Rollup rollup(Long tenderId) {
        List<TenderPart> parts = tenderPartRepository.findByTenderIdOrderBySortOrderAscIdAsc(tenderId);
        List<PartSummary> summaries = parts.stream().map(part -> {
            List<TenderParticipant> ps = tenderParticipantRepository.findByPartIdOrderByOwnCompanyDescIdAsc(part.getId());
            TenderParticipant own = ps.stream().filter(p -> Boolean.TRUE.equals(p.getOwnCompany())).findFirst().orElse(null);
            boolean we = own != null && Boolean.TRUE.equals(own.getParticipating());
            boolean weWon = own != null && Boolean.TRUE.equals(own.getWinner());
            boolean anyWinner = ps.stream().anyMatch(p -> Boolean.TRUE.equals(p.getWinner()));
            return new PartSummary(part.getEstimatedValue(), we, weWon, anyWinner);
        }).toList();
        return computeRollup(summaries);
    }

    private void seedOwnCompany(TenderPart part) {
        if (tenderParticipantRepository.existsByPartIdAndOwnCompanyTrue(part.getId())) {
            return;
        }
        TenderParticipant own = new TenderParticipant();
        own.setPart(part);
        own.setTender(part.getTender());
        own.setOwnCompany(true);
        own.setParticipating(true); // default: we're in every part unless the user unmarks it
        own.setWinner(false);
        own.setManufacturerName(ownCompanyName());
        tenderParticipantRepository.save(own);
    }

    private String ownCompanyName() {
        Long companyId = TenantContext.getCompanyId();
        if (companyId == null) {
            return "Our company";
        }
        return companyRepository.findById(companyId)
                .map(Company::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("Our company");
    }

    private void applyPart(TenderPart part, TenderPartRequestDto dto) {
        part.setPartNumber(dto.getPartNumber());
        part.setTitle(dto.getTitle());
        part.setDescription(dto.getDescription());
        part.setEstimatedValue(dto.getEstimatedValue());
        part.setSamplesRequired(dto.getSamplesRequired());
        applyRequirements(part, dto.getRequirements());
    }

    /** Replace-all the part's requirements from the request (blank rows are skipped). */
    private void applyRequirements(TenderPart part, List<TenderRequirementRequestDto> requested) {
        part.getRequirements().clear();
        if (requested == null) {
            return;
        }
        // Sample counts only make sense when the part requires samples; drop them otherwise.
        boolean samplesRequired = Boolean.TRUE.equals(part.getSamplesRequired());
        int order = 0;
        for (TenderRequirementRequestDto dto : requested) {
            if (dto.getDescription() == null || dto.getDescription().isBlank()) {
                continue;
            }
            TenderRequirement requirement = new TenderRequirement();
            requirement.setPart(part);
            requirement.setDescription(dto.getDescription().trim());
            requirement.setQuantity(dto.getQuantity());
            requirement.setUnit(dto.getUnit() == null || dto.getUnit().isBlank() ? null : dto.getUnit().trim());
            requirement.setSampleQuantity(samplesRequired ? dto.getSampleQuantity() : null);
            requirement.setSortOrder(order++);
            part.getRequirements().add(requirement);
        }
    }

    private Tender requireTender(Long tenderId) {
        return tenderRepository.findById(tenderId)
                .orElseThrow(() -> new ResourceNotFoundException("Tender not found with id: " + tenderId));
    }

    private TenderPart requirePart(Long tenderId, Long partId) {
        TenderPart part = tenderPartRepository.findById(partId)
                .orElseThrow(() -> new ResourceNotFoundException("Tender part not found with id: " + partId));
        if (!part.getTender().getId().equals(tenderId)) {
            throw new BadRequestException("error.tender.partMismatch");
        }
        return part;
    }

    private TenderPartResponseDto toDto(TenderPart part) {
        List<TenderRequirementResponseDto> requirements = tenderRequirementRepository
                .findByPartIdOrderBySortOrderAscIdAsc(part.getId()).stream()
                .map(r -> new TenderRequirementResponseDto(r.getId(), r.getDescription(), r.getQuantity(), r.getUnit(), r.getSampleQuantity(), r.getSortOrder()))
                .toList();
        List<TenderParticipantResponseDto> participants = tenderParticipantRepository
                .findByPartIdOrderByOwnCompanyDescIdAsc(part.getId()).stream()
                .map(TenderParticipantService::toDto)
                .toList();
        return new TenderPartResponseDto(
                part.getId(),
                part.getPartNumber(),
                part.getTitle(),
                part.getDescription(),
                part.getEstimatedValue(),
                part.getSamplesRequired(),
                part.getSortOrder(),
                requirements,
                participants
        );
    }
}
