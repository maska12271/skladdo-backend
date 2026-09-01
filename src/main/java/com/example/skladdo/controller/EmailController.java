package com.example.skladdo.controller;

import com.example.skladdo.dto.EmailTemplateDto;
import com.example.skladdo.dto.RescheduleEmailRequest;
import com.example.skladdo.dto.ScheduledEmailDto;
import com.example.skladdo.dto.SendEmailRequest;
import com.example.skladdo.dto.SendEmailResult;
import com.example.skladdo.dto.SentEmailBatchDetailDto;
import com.example.skladdo.dto.SentEmailBatchDto;
import com.example.skladdo.dto.SentEmailDetailDto;
import com.example.skladdo.dto.SentEmailDto;
import com.example.skladdo.model.SentEmailStatus;
import com.example.skladdo.service.EmailSendingService;
import com.example.skladdo.service.EmailTemplateService;
import com.example.skladdo.service.ScheduledEmailService;
import com.example.skladdo.service.SentEmailService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Email feature: the reusable template library, sending outreach to clients and manufacturers (single or
 * bulk, now or queued for later), and the sent-email audit trail. All endpoints are gated by the
 * {@code MANUFACTURER_EMAILS} permission module; the public tracking-pixel and inbound-reply endpoints
 * live in their own (unauthenticated) controllers.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Emails")
public class EmailController {

    private final EmailTemplateService templateService;
    private final EmailSendingService sendingService;
    private final SentEmailService sentEmailService;
    private final ScheduledEmailService scheduledEmailService;

    public EmailController(EmailTemplateService templateService,
                           EmailSendingService sendingService,
                           SentEmailService sentEmailService,
                           ScheduledEmailService scheduledEmailService) {
        this.templateService = templateService;
        this.sendingService = sendingService;
        this.sentEmailService = sentEmailService;
        this.scheduledEmailService = scheduledEmailService;
    }

    // --- Templates -------------------------------------------------------------------------------

    @GetMapping("/email-templates")
    @PreAuthorize("@perm.canView(authentication, 'MANUFACTURER_EMAILS')")
    public List<EmailTemplateDto> listTemplates() {
        return templateService.findAll();
    }

    @PostMapping("/email-templates")
    @PreAuthorize("@perm.canEdit(authentication, 'MANUFACTURER_EMAILS')")
    public EmailTemplateDto createTemplate(@Valid @RequestBody EmailTemplateDto request) {
        return templateService.create(request);
    }

    @PutMapping("/email-templates/{id}")
    @PreAuthorize("@perm.canEdit(authentication, 'MANUFACTURER_EMAILS')")
    public EmailTemplateDto updateTemplate(@PathVariable Long id, @Valid @RequestBody EmailTemplateDto request) {
        return templateService.update(id, request);
    }

    @DeleteMapping("/email-templates/{id}")
    @PreAuthorize("@perm.canDelete(authentication, 'MANUFACTURER_EMAILS')")
    public void deleteTemplate(@PathVariable Long id) {
        templateService.delete(id);
    }

    // --- Sending ---------------------------------------------------------------------------------

    /**
     * Sends one email per recipient - or queues the whole send for later, when the request names a
     * {@code scheduledAt}. Multipart so the compose form can attach files: the {@code request} part
     * carries the JSON body, {@code files} carries any attachments (optional).
     *
     * <p>One endpoint rather than two, because it is one form: the only difference a user makes is
     * picking a time, and the response shape says which happened ({@code scheduledId} set means nothing
     * has been attempted yet).</p>
     */
    @PostMapping(value = "/emails/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@perm.canCreate(authentication, 'MANUFACTURER_EMAILS')")
    public SendEmailResult send(@Valid @RequestPart("request") SendEmailRequest request,
                                @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        if (request.scheduledAt() != null) {
            return SendEmailResult.scheduled(scheduledEmailService.schedule(request, files).getId());
        }
        return sendingService.sendBulk(request.recipientType(), request.recipientIds(), request.templateId(),
                request.subject(), request.body(), request.contactId(), files);
    }

    // --- Scheduled sends -------------------------------------------------------------------------

    /**
     * What is still queued, soonest first. Non-managers see only what they scheduled themselves.
     * {@code clientId} narrows this to one client's own scheduled reminders (the client detail page).
     */
    @GetMapping("/scheduled-emails")
    @PreAuthorize("@perm.canView(authentication, 'MANUFACTURER_EMAILS')")
    public List<ScheduledEmailDto> listScheduled(@RequestParam(required = false) Long clientId) {
        return clientId != null ? scheduledEmailService.list(clientId) : scheduledEmailService.list();
    }

    /**
     * Moves a queued send to a new time. Changing anything else - recipients, message, attachments -
     * means cancelling and composing again, which is the same amount of work for the user and a great
     * deal less machinery than an edit mode that has to re-upload files.
     */
    @PutMapping("/scheduled-emails/{id}")
    @PreAuthorize("@perm.canCreate(authentication, 'MANUFACTURER_EMAILS')")
    public ScheduledEmailDto reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleEmailRequest request) {
        return scheduledEmailService.reschedule(id, request.scheduledAt());
    }

    /** Drops a queued send before it fires, along with the attachments stored for it. */
    @DeleteMapping("/scheduled-emails/{id}")
    @PreAuthorize("@perm.canCreate(authentication, 'MANUFACTURER_EMAILS')")
    public void cancelScheduled(@PathVariable Long id) {
        scheduledEmailService.cancel(id);
    }

    // --- Sent-email audit trail ------------------------------------------------------------------

    /**
     * Per-recipient sent-email list (one row per recipient). Used by the per-user and per-partner email
     * lists, which filter by {@code senderId}/{@code manufacturerId}/{@code clientId}. The main emails page
     * uses the grouped {@code /sent-email-batches} list instead.
     */
    @GetMapping("/sent-emails")
    @PreAuthorize("@perm.canView(authentication, 'MANUFACTURER_EMAILS')")
    public Page<SentEmailDto> listSentEmails(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long manufacturerId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long senderId,
            @RequestParam(required = false) SentEmailStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "sentAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return sentEmailService.findAll(search, manufacturerId, clientId, senderId, status, pageable);
    }

    @GetMapping("/sent-emails/{id}")
    @PreAuthorize("@perm.canView(authentication, 'MANUFACTURER_EMAILS')")
    public SentEmailDetailDto getSentEmail(@PathVariable Long id) {
        return sentEmailService.getDetail(id);
    }

    // --- Batched sent-email view (one row per bulk send) -----------------------------------------

    /**
     * Sent-email list, grouped so each bulk send shows as one row with aggregate open/reply counts. Ordered
     * newest-first server-side (the grouped aggregation defines its own order), so no client sort params.
     */
    @GetMapping("/sent-email-batches")
    @PreAuthorize("@perm.canView(authentication, 'MANUFACTURER_EMAILS')")
    public Page<SentEmailBatchDto> listBatches(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long senderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        return sentEmailService.findBatches(search, senderId, pageable);
    }

    /** Per-recipient breakdown of one bulk send (who it went to, who opened it, who replied). */
    @GetMapping("/sent-email-batches/{batchId}")
    @PreAuthorize("@perm.canView(authentication, 'MANUFACTURER_EMAILS')")
    public SentEmailBatchDetailDto getBatch(@PathVariable String batchId) {
        return sentEmailService.getBatchDetail(batchId);
    }
}
