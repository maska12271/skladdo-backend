package com.example.kladdo.service;

import com.example.kladdo.dto.AuditLogDto;
import com.example.kladdo.model.AuditAction;
import com.example.kladdo.model.AuditLog;
import com.example.kladdo.repository.AuditLogRepository;
import com.example.kladdo.security.CustomUserDetails;
import com.example.kladdo.security.SecurityUtil;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes and reads the company-wide audit trail.
 *
 * <p><strong>Writing.</strong> {@link #record} is called explicitly from the service methods worth
 * recording. It joins the caller's transaction on purpose: if the business change rolls back, its audit
 * row rolls back with it, so the trail never claims something happened that did not.</p>
 *
 * <p><strong>Entity types</strong> are the {@code ENTITY_*} constants below rather than an enum, so
 * auditing a new kind of record never needs a schema migration. Keep them in sync with the frontend's
 * filter list ({@code constants/audit.js}).</p>
 */
@Service
public class AuditService {

    public static final String ENTITY_USER = "USER";
    public static final String ENTITY_SALES_ORDER = "SALES_ORDER";
    public static final String ENTITY_PURCHASE_ORDER = "PURCHASE_ORDER";
    public static final String ENTITY_CLIENT = "CLIENT";
    public static final String ENTITY_MANUFACTURER = "MANUFACTURER";
    public static final String ENTITY_PRODUCT = "PRODUCT";
    public static final String ENTITY_TENDER = "TENDER";
    /** The company's own identity (a rename is worth recording); its *settings* deliberately are not. */
    public static final String ENTITY_COMPANY = "COMPANY";
    /** Granting or ending another company's access to our data via a warehouse connection. */
    public static final String ENTITY_WAREHOUSE_PARTNER = "WAREHOUSE_PARTNER";

    /** Cap matching the column, so an over-long detail is trimmed rather than failing the whole write. */
    private static final int MAX_DETAILS = 500;

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one change against the calling company. {@code details} is a short, language-neutral
     * identifier (an email, an order number, a status transition) - never a translated sentence, since the
     * row may later be read in a different language. Pass {@code null} when there is nothing to add.
     */
    @Transactional
    public void record(String entityType, Long entityId, AuditAction action, String details) {
        AuditLog log = new AuditLog();
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setAction(action);
        log.setDetails(trim(details));
        log.setCreatedAt(Instant.now());

        // A background job or public endpoint has no principal; the row is still worth keeping, just
        // without an actor.
        CustomUserDetails actor = currentActorOrNull();
        if (actor != null) {
            log.setActorUserId(actor.getId());
            log.setActorName(actor.getFullName() != null && !actor.getFullName().isBlank()
                    ? actor.getFullName()
                    : actor.getEmail());
        }
        repository.save(log);
    }

    private static CustomUserDetails currentActorOrNull() {
        try {
            return SecurityUtil.currentUser();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static String trim(String details) {
        if (details == null) {
            return null;
        }
        return details.length() <= MAX_DETAILS ? details : details.substring(0, MAX_DETAILS);
    }

    // --- Reading (the admin viewer) ---------------------------------------------------------------

    /**
     * Filtered, paged trail. Every filter is optional; multi-value filters are OR-ed within a filter and
     * AND-ed across filters, matching the other server-paginated lists.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogDto> search(String search,
                                    List<String> entityTypes,
                                    List<AuditAction> actions,
                                    List<Long> actorIds,
                                    Pageable pageable) {
        return repository.findAll(specification(search, entityTypes, actions, actorIds), pageable)
                .map(AuditLogDto::from);
    }

    private static Specification<AuditLog> specification(String search,
                                                         List<String> entityTypes,
                                                         List<AuditAction> actions,
                                                         List<Long> actorIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("details")), like),
                        cb.like(cb.lower(root.get("actorName")), like)));
            }
            if (entityTypes != null && !entityTypes.isEmpty()) {
                predicates.add(root.get("entityType").in(entityTypes));
            }
            if (actions != null && !actions.isEmpty()) {
                predicates.add(root.get("action").in(actions));
            }
            if (actorIds != null && !actorIds.isEmpty()) {
                predicates.add(root.get("actorUserId").in(actorIds));
            }
            return predicates.isEmpty() ? null : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Distinct actors present in the trail, for the viewer's "who" filter. */
    @Transactional(readOnly = true)
    public List<ActorOption> actors() {
        return repository.findDistinctActors().stream()
                .map(row -> new ActorOption((Long) row[0], (String) row[1]))
                .toList();
    }

    /** One entry in the viewer's actor filter. */
    public record ActorOption(Long id, String name) {
    }
}
