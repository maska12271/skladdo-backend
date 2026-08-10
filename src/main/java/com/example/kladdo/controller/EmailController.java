package com.example.kladdo.controller;

import com.example.kladdo.dto.EmailTemplateDto;
import com.example.kladdo.dto.SendEmailRequest;
import com.example.kladdo.dto.SendEmailResult;
import com.example.kladdo.dto.SentEmailBatchDetailDto;
import com.example.kladdo.dto.SentEmailBatchDto;
import com.example.kladdo.dto.SentEmailDetailDto;
import com.example.kladdo.dto.SentEmailDto;
import com.example.kladdo.model.SentEmailStatus;
import com.example.kladdo.service.EmailSendingService;
import com.example.kladdo.service.EmailTemplateService;
import com.example.kladdo.service.SentEmailService;
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
 * Manufacturer email feature: the reusable template library, sending outreach (single or bulk), and the
 * sent-email audit trail. All endpoints are gated by the {@code MANUFACTURER_EMAILS} permission module;
 * the public tracking-pixel and inbound-reply endpoints live in their own (unauthenticated) controllers.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Manufacturer Emails")
public class EmailController {

    private final EmailTemplateService templateService;
    private final EmailSendingService sendingService;
    private final SentEmailService sentEmailService;

    public EmailController(EmailTemplateService templateService,
                           EmailSendingService sendingService,
                           SentEmailService sentEmailService) {
        this.templateService = templateService;
        this.sendingService = sendingService;
        this.sentEmailService = sentEmailService;
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
     * Sends one email per manufacturer. Multipart so the compose form can attach files: the {@code request}
     * part carries the JSON body, {@code files} carries any attachments (optional).
     */
    @PostMapping(value = "/manufacturers/emails/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@perm.canCreate(authentication, 'MANUFACTURER_EMAILS')")
    public SendEmailResult send(@Valid @RequestPart("request") SendEmailRequest request,
                                @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return sendingService.sendBulk(request.manufacturerIds(), request.templateId(),
                request.subject(), request.body(), files);
    }

    // --- Sent-email audit trail ------------------------------------------------------------------

    /**
     * Per-recipient sent-email list (one row per recipient). Used by the per-user and per-manufacturer email
     * lists, which filter by {@code senderId}/{@code manufacturerId}. The main emails page uses the grouped
     * {@code /sent-email-batches} list instead.
     */
    @GetMapping("/sent-emails")
    @PreAuthorize("@perm.canView(authentication, 'MANUFACTURER_EMAILS')")
    public Page<SentEmailDto> listSentEmails(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long manufacturerId,
            @RequestParam(required = false) Long senderId,
            @RequestParam(required = false) SentEmailStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "sentAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return sentEmailService.findAll(search, manufacturerId, senderId, status, pageable);
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
