package com.example.skladdo.service;

import com.example.skladdo.dto.SentEmailBatchDetailDto;
import com.example.skladdo.dto.SentEmailBatchDto;
import com.example.skladdo.dto.SentEmailDetailDto;
import com.example.skladdo.dto.SentEmailDto;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.SentEmail;
import com.example.skladdo.model.SentEmailStatus;
import com.example.skladdo.repository.EmailReplyRepository;
import com.example.skladdo.repository.SentEmailRepository;
import com.example.skladdo.security.SecurityUtil;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Read access to the sent-email audit trail. Non-managers only ever see their own sends; owners and
 * administrators see everyone's in their company (the same "manager sees all" bypass used across the app).
 * Sent emails are immutable - there is no update or delete here by design.
 */
@Service
public class SentEmailService {

    private final SentEmailRepository sentEmailRepository;
    private final EmailReplyRepository emailReplyRepository;

    public SentEmailService(SentEmailRepository sentEmailRepository,
                            EmailReplyRepository emailReplyRepository) {
        this.sentEmailRepository = sentEmailRepository;
        this.emailReplyRepository = emailReplyRepository;
    }

    /**
     * Paged, filterable per-recipient list (one row per {@link SentEmail}). {@code search} matches subject /
     * manufacturer name / recipient; the {@code manufacturerId}, {@code senderId} and {@code status} filters
     * are optional. Non-managers are always additionally constrained to their own sends regardless of the
     * {@code senderId} filter. Used by the per-user and per-manufacturer email lists; the main emails page
     * uses {@link #findBatches} instead.
     */
    @Transactional(readOnly = true)
    public Page<SentEmailDto> findAll(String search, Long manufacturerId, Long senderId,
                                      SentEmailStatus status, Pageable pageable) {
        Long ownScopeUserId = SecurityUtil.currentUserIsManager() ? null : SecurityUtil.currentUserId();

        Specification<SentEmail> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("subjectSnapshot")), like),
                        cb.like(cb.lower(root.get("manufacturerNameSnapshot")), like),
                        cb.like(cb.lower(root.get("recipientEmail")), like)
                ));
            }
            if (manufacturerId != null) {
                predicates.add(cb.equal(root.get("manufacturer").get("id"), manufacturerId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            // A non-manager is locked to their own rows; a manager may optionally filter by a sender.
            Long effectiveSenderId = ownScopeUserId != null ? ownScopeUserId : senderId;
            if (effectiveSenderId != null) {
                predicates.add(cb.equal(root.get("sentById"), effectiveSenderId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return sentEmailRepository.findAll(spec, pageable).map(SentEmailDto::from);
    }

    /**
     * Paged sent-email list, one row per bulk send (grouped by {@code batchId}), newest first. {@code search}
     * matches subject / manufacturer name / recipient across a batch's recipients; {@code senderId} optionally
     * restricts to one sender. Non-managers are always additionally constrained to their own sends regardless
     * of the {@code senderId} filter.
     */
    @Transactional(readOnly = true)
    public Page<SentEmailBatchDto> findBatches(String search, Long senderId, Pageable pageable) {
        Long ownScopeUserId = SecurityUtil.currentUserIsManager() ? null : SecurityUtil.currentUserId();
        // A non-manager is locked to their own rows; a manager may optionally filter by a sender.
        Long effectiveSenderId = ownScopeUserId != null ? ownScopeUserId : senderId;
        String like = (search != null && !search.isBlank()) ? "%" + search.toLowerCase() + "%" : null;

        return sentEmailRepository.findBatches(effectiveSenderId, like, pageable).map(SentEmailBatchDto::fromRow);
    }

    /**
     * Full breakdown of one bulk send: shared metadata plus per-recipient delivery/open/reply state. 404s
     * (not 403) if the batch is empty or a non-manager requests a batch that isn't theirs.
     */
    @Transactional(readOnly = true)
    public SentEmailBatchDetailDto getBatchDetail(String batchId) {
        List<SentEmail> rows = sentEmailRepository.findByBatchIdOrderByIdAsc(batchId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Email batch not found: " + batchId);
        }
        if (!SecurityUtil.currentUserIsManager()
                && !SecurityUtil.currentUserId().equals(rows.get(0).getSentById())) {
            throw new ResourceNotFoundException("Email batch not found: " + batchId);
        }
        return SentEmailBatchDetailDto.from(batchId, rows);
    }

    /** Detail with reply thread. 404s (not 403) if a non-manager requests someone else's email. */
    @Transactional(readOnly = true)
    public SentEmailDetailDto getDetail(Long id) {
        SentEmail email = sentEmailRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sent email not found with id: " + id));
        if (!SecurityUtil.currentUserIsManager()
                && !SecurityUtil.currentUserId().equals(email.getSentById())) {
            throw new ResourceNotFoundException("Sent email not found with id: " + id);
        }
        return SentEmailDetailDto.from(email, emailReplyRepository.findBySentEmailIdOrderByReceivedAtAsc(id));
    }
}
