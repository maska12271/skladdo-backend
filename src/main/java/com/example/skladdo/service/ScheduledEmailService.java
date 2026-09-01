package com.example.skladdo.service;

import com.example.skladdo.dto.ScheduledEmailDto;
import com.example.skladdo.dto.SendEmailRequest;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.LocalizedException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.ScheduledEmail;
import com.example.skladdo.model.ScheduledEmailAttachment;
import com.example.skladdo.model.ScheduledEmailStatus;
import com.example.skladdo.repository.ScheduledEmailRepository;
import com.example.skladdo.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Queued sends: putting one in the queue, showing what is in it, and running the ones that come due.
 *
 * <p>The dispatch half runs on a background thread with a tenant bound but nobody authenticated, so it
 * never reads the security context - it hands {@code EmailSendingService.sendNow} the scheduling user
 * explicitly, and the resulting audit rows are credited to them rather than to the scheduler.</p>
 *
 * <p>No method here is {@code @Transactional}, matching {@link EmailSendingService}: a send is slow
 * network I/O, and holding a transaction open across it would pin a connection for the length of a
 * whole batch. Each repository call commits on its own, which is exactly what the claim/send/resolve
 * sequence in {@link #dispatchOne} needs.</p>
 */
@Service
public class ScheduledEmailService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledEmailService.class);

    private static final String ATTACHMENT_CATEGORY = "email-attachments";

    private final ScheduledEmailRepository repository;
    private final EmailSendingService sendingService;
    private final StorageService storageService;
    private final PlanService planService;
    private final MessageSource messageSource;
    private final JdbcTemplate jdbc;

    public ScheduledEmailService(ScheduledEmailRepository repository,
                                 EmailSendingService sendingService,
                                 StorageService storageService,
                                 PlanService planService,
                                 MessageSource messageSource,
                                 JdbcTemplate jdbc) {
        this.repository = repository;
        this.sendingService = sendingService;
        this.storageService = storageService;
        this.planService = planService;
        this.messageSource = messageSource;
        this.jdbc = jdbc;
    }

    // --- Queueing (request time) -----------------------------------------------------------------

    /**
     * Queues {@code request} to be sent at {@code request.scheduledAt()}, storing any attachments so the
     * dispatcher can attach them later. Refuses a time already past - a schedule that fires the instant
     * it is made is not what the user asked for, and silently sending it now would be worse.
     */
    public ScheduledEmail schedule(SendEmailRequest request, List<MultipartFile> files) {
        planService.assertEmailsEnabled();
        Instant when = request.scheduledAt();
        if (when == null || !when.isAfter(Instant.now())) {
            throw new BadRequestException("error.email.scheduleInPast");
        }

        ScheduledEmail row = new ScheduledEmail();
        row.setRecipientType(request.recipientType());
        row.setRecipientIds(new LinkedHashSet<>(request.recipientIds()));
        row.setTemplateId(request.templateId());
        row.setContactId(request.contactId());
        row.setSubject(request.subject());
        row.setBody(request.body());
        row.setScheduledAt(when);
        row.setServiceId(request.serviceId());
        row.setStatus(ScheduledEmailStatus.PENDING);
        row.setAttachments(storeAttachments(files));
        return repository.save(row);
    }

    /** Uploads each attachment once, now, so the dispatcher has bytes to send whenever it gets there. */
    private List<ScheduledEmailAttachment> storeAttachments(List<MultipartFile> files) {
        List<ScheduledEmailAttachment> stored = new ArrayList<>();
        if (files == null) {
            return stored;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String key = storageService.store(file, ATTACHMENT_CATEGORY);
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment";
            stored.add(new ScheduledEmailAttachment(key, name, file.getContentType()));
        }
        return stored;
    }

    // --- Reading and editing (request time) ------------------------------------------------------

    /** What is still queued, soonest first. Non-managers see only what they scheduled themselves. */
    public List<ScheduledEmailDto> list() {
        List<ScheduledEmail> rows = SecurityUtil.currentUserIsManager()
                ? repository.findAllByOrderByScheduledAtAsc()
                : repository.findByCreatedByIdOrderByScheduledAtAsc(SecurityUtil.currentUserId());
        return rows.stream().map(this::toDto).toList();
    }

    /** Same, narrowed to what is queued for one client - powers the client detail page's own section. */
    public List<ScheduledEmailDto> list(Long clientId) {
        Long createdById = SecurityUtil.currentUserIsManager() ? null : SecurityUtil.currentUserId();
        return repository.findByClientId(clientId, createdById).stream().map(this::toDto).toList();
    }

    /** Moves a queued send to a new time. Only a PENDING row can move; one already firing cannot. */
    public ScheduledEmailDto reschedule(Long id, Instant scheduledAt) {
        ScheduledEmail row = requireOwnPending(id);
        if (scheduledAt == null || !scheduledAt.isAfter(Instant.now())) {
            throw new BadRequestException("error.email.scheduleInPast");
        }
        row.setScheduledAt(scheduledAt);
        return toDto(repository.save(row));
    }

    /**
     * Drops a queued send and the attachments stored for it. The row is deleted rather than marked
     * cancelled: nothing was sent, so there is no history to keep - and who cancelled what belongs in
     * the audit log, not in a queue of outstanding work.
     */
    public void cancel(Long id) {
        ScheduledEmail row = requireOwnPending(id);
        repository.delete(row);
        deleteAttachments(row);
    }

    /** 404s (not 403s) for someone else's row, mirroring {@code SentEmailService}. */
    private ScheduledEmail requireOwnPending(Long id) {
        ScheduledEmail row = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Scheduled email not found with id: " + id));
        if (!SecurityUtil.currentUserIsManager()
                && !SecurityUtil.currentUserId().equals(row.getCreatedById())) {
            throw new ResourceNotFoundException("Scheduled email not found with id: " + id);
        }
        if (row.getStatus() != ScheduledEmailStatus.PENDING) {
            throw new BadRequestException("error.email.scheduleNotPending");
        }
        return row;
    }

    private ScheduledEmailDto toDto(ScheduledEmail row) {
        return ScheduledEmailDto.from(row, translate(row.getFailureReason()));
    }

    /**
     * Resolves a stored failure key in the reader's language, falling back to the stored text itself -
     * which is what an SMTP-level message already is, and is more use raw than replaced by a generic
     * apology.
     */
    private String translate(String reason) {
        if (reason == null || reason.isBlank()) {
            return reason;
        }
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(reason, null, reason, locale);
    }

    // --- Dispatch (background thread) ------------------------------------------------------------

    /**
     * The companies that have a send due, found without a tenant bound.
     *
     * <p>Raw JDBC on purpose. {@link ScheduledEmail} is {@code @TenantId}-scoped, so a JPA query can only
     * ever see one company - and the alternative, binding every company in turn to ask, is a query per
     * company per minute forever. Same shape as {@code EmailTokenTenantResolver}: resolve the tenant
     * outside the filter first, then bind it. On a quiet minute this is the only query that runs.</p>
     */
    public List<Long> companiesWithDueSends(Instant now) {
        return jdbc.queryForList(
                "SELECT DISTINCT company_id FROM scheduled_email WHERE status = ? AND scheduled_at <= ?",
                Long.class, ScheduledEmailStatus.PENDING.name(), java.sql.Timestamp.from(now));
    }

    /**
     * Sends every schedule that has come due for the currently bound tenant, and reports how many were
     * attempted. Each is isolated: one that cannot be sent is recorded and the rest still go.
     */
    public int dispatchDueForCurrentTenant() {
        List<ScheduledEmail> due = repository
                .findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                        ScheduledEmailStatus.PENDING, Instant.now());
        int dispatched = 0;
        for (ScheduledEmail row : due) {
            if (dispatchOne(row)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    /**
     * Claims one schedule, sends it, and clears it. Returns whether the send was attempted.
     *
     * <p>The claim is committed before the first message goes out. That ordering is the whole safety
     * argument: if the process dies mid-batch the row is left in {@code SENDING}, which the dispatcher
     * skips, so the recipients who already received it never get a second copy. A stuck row is visible
     * in the Scheduled tab; a duplicated mailshot would not be recoverable at all.</p>
     */
    private boolean dispatchOne(ScheduledEmail row) {
        row.setStatus(ScheduledEmailStatus.SENDING);
        repository.save(row);

        try {
            sendingService.sendNow(
                    row.getRecipientType(),
                    new ArrayList<>(row.getRecipientIds()),
                    row.getTemplateId(),
                    row.getSubject(),
                    row.getBody(),
                    row.getContactId(),
                    readAttachments(row),
                    row.getCreatedById(),
                    row.getCompanyId());
            // It fired, so the SentEmail rows are now the record and this one has nothing left to say.
            repository.delete(row);
            deleteAttachments(row);
            return true;
        } catch (Exception e) {
            String reason = e instanceof LocalizedException localized
                    ? localized.getMessageKey()
                    : e.getMessage();
            log.warn("Scheduled email {} (company {}) could not be sent: {}",
                    row.getId(), row.getCompanyId(), reason);
            row.setStatus(ScheduledEmailStatus.FAILED);
            row.setFailureReason(truncate(reason, 2000));
            repository.save(row);
            return false;
        }
    }

    /** Pulls the stored bytes back so they can be attached, exactly as an immediate send would. */
    private List<EmailSendingService.Attachment> readAttachments(ScheduledEmail row) {
        List<EmailSendingService.Attachment> out = new ArrayList<>();
        for (ScheduledEmailAttachment att : row.getAttachments()) {
            try (InputStream in = storageService.open(att.getStorageKey()).content()) {
                out.add(new EmailSendingService.Attachment(
                        att.getFileName(), att.getContentType(), in.readAllBytes()));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Could not read scheduled attachment " + att.getFileName(), e);
            }
        }
        return out;
    }

    /**
     * Removes the stored bytes for a schedule that is done with them. Best-effort: the row is already
     * gone by the time this runs, and a key left behind is wasted space, not a broken reference.
     */
    private void deleteAttachments(ScheduledEmail row) {
        for (ScheduledEmailAttachment att : row.getAttachments()) {
            try {
                storageService.delete(att.getStorageKey());
            } catch (Exception e) {
                log.warn("Could not delete scheduled attachment {}: {}", att.getStorageKey(), e.toString());
            }
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
