package com.example.kladdo.service;

import com.example.kladdo.dto.SendEmailResult;
import com.example.kladdo.dto.TestEmailResult;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ResourceNotFoundException;
import com.example.kladdo.model.Company;
import com.example.kladdo.model.CompanySettings;
import com.example.kladdo.model.EmailTemplate;
import com.example.kladdo.model.Manufacturer;
import com.example.kladdo.model.SentEmail;
import com.example.kladdo.model.SentEmailStatus;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.EmailTemplateRepository;
import com.example.kladdo.repository.ManufacturerRepository;
import com.example.kladdo.repository.SentEmailRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.CustomUserDetails;
import com.example.kladdo.security.EncryptionService;
import com.example.kladdo.security.SecurityUtil;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates sending manufacturer outreach: renders the (per-recipient) subject/body, embeds a
 * tracking pixel, sends through the calling company's own SMTP server, and records an immutable
 * {@link SentEmail} audit row for every recipient - successes and failures alike.
 *
 * <p>Bulk sends build the {@link JavaMailSenderImpl} once (SMTP settings don't change mid-batch) but
 * send and catch per recipient, so one bad address or timeout never aborts the rest of the batch.</p>
 */
@Service
public class EmailSendingService {

    private static final Logger log = LoggerFactory.getLogger(EmailSendingService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ManufacturerRepository manufacturerRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final SentEmailRepository sentEmailRepository;
    private final CompanySettingsService companySettingsService;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final CompanyMailSenderFactory mailSenderFactory;
    private final EmailTemplateRenderer renderer;
    private final PlanService planService;

    private final String inboundDomain;
    private final String publicBaseUrl;

    public EmailSendingService(ManufacturerRepository manufacturerRepository,
                               EmailTemplateRepository emailTemplateRepository,
                               SentEmailRepository sentEmailRepository,
                               CompanySettingsService companySettingsService,
                               CompanyRepository companyRepository,
                               UserRepository userRepository,
                               EncryptionService encryptionService,
                               CompanyMailSenderFactory mailSenderFactory,
                               EmailTemplateRenderer renderer,
                               PlanService planService,
                               @Value("${app.mail.inbound-domain}") String inboundDomain,
                               @Value("${app.public-base-url}") String publicBaseUrl) {
        this.manufacturerRepository = manufacturerRepository;
        this.emailTemplateRepository = emailTemplateRepository;
        this.sentEmailRepository = sentEmailRepository;
        this.companySettingsService = companySettingsService;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.mailSenderFactory = mailSenderFactory;
        this.renderer = renderer;
        this.planService = planService;
        this.inboundDomain = inboundDomain;
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    }

    /** An email attachment read fully into memory once, so it can be attached to every recipient's message. */
    private record Attachment(String name, String contentType, byte[] bytes) {}

    /**
     * Sends one email per manufacturer id. Renders {@code subject}/{@code body} per recipient, records an
     * audit row for each, and returns a per-recipient summary. Throws {@link BadRequestException} only for
     * whole-batch problems (SMTP not configured); individual send failures are captured in the result.
     */
    public SendEmailResult sendBulk(List<Long> manufacturerIds, Long templateId, String subject, String body,
                                    List<MultipartFile> files) {
        planService.assertEmailsEnabled();
        CompanySettings settings = companySettingsService.getOrCreate();
        requireSmtpConfigured(settings);

        String password = settings.getSmtpPasswordEncrypted() != null && !settings.getSmtpPasswordEncrypted().isBlank()
                ? encryptionService.decrypt(settings.getSmtpPasswordEncrypted())
                : null;
        JavaMailSenderImpl sender = mailSenderFactory.create(settings, password);

        CustomUserDetails currentUser = SecurityUtil.currentUser();
        Company company = companyRepository.findById(SecurityUtil.currentCompanyId()).orElse(null);
        EmailTemplate template = resolveTemplate(templateId);
        // The sender's personal signature (appended to every email) and any attachments, read once.
        String signature = userRepository.findById(currentUser.getId())
                .map(User::getEmailSignature).orElse(null);
        List<Attachment> attachments = readAttachments(files);

        // One id shared by every recipient row of this send, so the sent-emails list can group them.
        String batchId = UUID.randomUUID().toString();

        List<SendEmailResult.RecipientResult> results = new ArrayList<>();
        int sent = 0;
        int failed = 0;

        for (Long manufacturerId : manufacturerIds) {
            Manufacturer manufacturer = manufacturerRepository.findById(manufacturerId)
                    .orElseThrow(() -> new ResourceNotFoundException("Manufacturer not found with id: " + manufacturerId));

            SentEmail record = sendOne(sender, settings, manufacturer, template, subject, body,
                    currentUser, company, signature, attachments, batchId);
            if (record.getStatus() == SentEmailStatus.SENT) {
                sent++;
            } else {
                failed++;
            }
            results.add(new SendEmailResult.RecipientResult(
                    manufacturer.getId(),
                    manufacturer.getName(),
                    record.getRecipientEmail(),
                    record.getStatus().name(),
                    record.getFailureReason(),
                    record.getId()));
        }

        return new SendEmailResult(sent, failed, results);
    }

    /**
     * Sends a plain test email to {@code recipient} using the company's saved SMTP settings, so an admin
     * can verify the configuration. Throws {@link BadRequestException} if SMTP is not configured; a send
     * failure is returned (not thrown) with the SMTP error detail so the UI can display it.
     */
    public TestEmailResult sendTest(String recipient) {
        CompanySettings settings = companySettingsService.getOrCreate();
        requireSmtpConfigured(settings);

        String password = settings.getSmtpPasswordEncrypted() != null && !settings.getSmtpPasswordEncrypted().isBlank()
                ? encryptionService.decrypt(settings.getSmtpPasswordEncrypted())
                : null;
        JavaMailSenderImpl sender = mailSenderFactory.create(settings, password);

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (settings.getSmtpFromName() != null && !settings.getSmtpFromName().isBlank()) {
                helper.setFrom(settings.getSmtpFromAddress(), settings.getSmtpFromName());
            } else {
                helper.setFrom(settings.getSmtpFromAddress());
            }
            helper.setTo(recipient);
            helper.setSubject("Kladdo test email");
            helper.setText("This is a test email confirming your Kladdo email settings are working.");
            sender.send(message);
            return new TestEmailResult(true, null);
        } catch (Exception e) {
            log.warn("Test email to {} failed: {}", recipient, e.getMessage());
            return new TestEmailResult(false, e.getMessage());
        }
    }

    /**
     * Renders, sends (best-effort), and persists the audit row for a single recipient. Never throws:
     * a send failure is recorded on the row as {@link SentEmailStatus#FAILED} with a reason.
     */
    private SentEmail sendOne(JavaMailSenderImpl sender, CompanySettings settings, Manufacturer manufacturer,
                              EmailTemplate template, String rawSubject, String rawBody,
                              CustomUserDetails currentUser, Company company,
                              String signature, List<Attachment> attachments, String batchId) {
        String token = generateToken();
        Map<String, String> tokens = renderer.tokensFor(manufacturer, currentUser, company);
        String renderedSubject = renderer.renderPlain(rawSubject, tokens);
        // Body = message, then the sender's signature (also token-aware), then the tracking pixel.
        String renderedBody = renderer.render(rawBody, tokens);
        if (signature != null && !signature.isBlank()) {
            renderedBody = renderedBody + "<br><br>" + renderer.render(signature, tokens);
        }
        renderedBody = injectTrackingPixel(renderedBody, token);

        SentEmail record = new SentEmail();
        record.setBatchId(batchId);
        record.setManufacturer(manufacturer);
        record.setManufacturerNameSnapshot(manufacturer.getName());
        record.setRecipientEmail(manufacturer.getEmail() != null ? manufacturer.getEmail() : "");
        record.setTemplateId(template != null ? template.getId() : null);
        record.setSubjectSnapshot(renderedSubject);
        record.setBodySnapshot(renderedBody);
        record.setTrackingToken(token);
        if (!attachments.isEmpty()) {
            record.setAttachmentNames(attachments.stream().map(Attachment::name).collect(Collectors.joining(", ")));
        }

        try {
            if (manufacturer.getEmail() == null || manufacturer.getEmail().isBlank()) {
                throw new IllegalArgumentException("Manufacturer has no email address");
            }
            MimeMessage message = buildMessage(sender, settings, manufacturer.getEmail(), token,
                    renderedSubject, renderedBody, attachments);
            sender.send(message);
            record.setStatus(SentEmailStatus.SENT);
        } catch (Exception e) {
            log.warn("Failed to send email to manufacturer {} ({}): {}",
                    manufacturer.getId(), manufacturer.getEmail(), e.getMessage());
            record.setStatus(SentEmailStatus.FAILED);
            record.setFailureReason(truncate(e.getMessage(), 2000));
        }

        return sentEmailRepository.save(record);
    }

    private MimeMessage buildMessage(JavaMailSenderImpl sender, CompanySettings settings, String to,
                                     String token, String subject, String htmlBody,
                                     List<Attachment> attachments) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        // multipart=true is required for attachments (mixed) alongside the HTML body.
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        if (settings.getSmtpFromName() != null && !settings.getSmtpFromName().isBlank()) {
            helper.setFrom(settings.getSmtpFromAddress(), settings.getSmtpFromName());
        } else {
            helper.setFrom(settings.getSmtpFromAddress());
        }
        // Reply-To always points at the platform-controlled inbound domain so replies route through our
        // Mailgun inbound webhook (not the tenant's own SMTP domain). The token maps a reply back to this row.
        helper.setReplyTo("reply+" + token + "@" + inboundDomain);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        for (Attachment att : attachments) {
            ByteArrayResource resource = new ByteArrayResource(att.bytes());
            if (att.contentType() != null && !att.contentType().isBlank()) {
                helper.addAttachment(att.name(), resource, att.contentType());
            } else {
                helper.addAttachment(att.name(), resource);
            }
        }
        return message;
    }

    /** Reads each uploaded file fully into memory once (skipping empties), so it can be reused per recipient. */
    private List<Attachment> readAttachments(List<MultipartFile> files) {
        List<Attachment> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                String name = StringUtils.cleanPath(
                        file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment");
                out.add(new Attachment(name, file.getContentType(), file.getBytes()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not read attachment " + file.getOriginalFilename(), e);
            }
        }
        return out;
    }

    /**
     * Appends the 1x1 tracking-pixel img just before {@code </body>} (or at the end if there is no body
     * tag). Added here, after rendering, so it is never part of the user-editable template and can't be
     * accidentally stripped by editing the template text.
     */
    private String injectTrackingPixel(String html, String token) {
        String pixel = "<img src=\"" + publicBaseUrl + "/api/public/email-tracking/" + token + ".png\""
                + " width=\"1\" height=\"1\" alt=\"\" style=\"display:none\" />";
        if (html == null || html.isBlank()) {
            return pixel;
        }
        int bodyClose = html.toLowerCase().lastIndexOf("</body>");
        if (bodyClose >= 0) {
            return html.substring(0, bodyClose) + pixel + html.substring(bodyClose);
        }
        return html + pixel;
    }

    private EmailTemplate resolveTemplate(Long templateId) {
        if (templateId == null) {
            return null;
        }
        return emailTemplateRepository.findById(templateId)
                .orElseThrow(() -> new BadRequestException("error.email.templateNotFound"));
    }

    private void requireSmtpConfigured(CompanySettings settings) {
        if (!isSmtpConfigured(settings)) {
            throw new BadRequestException("error.email.smtpNotConfigured");
        }
    }

    /** SMTP is usable once host, port, username and from-address are all present. */
    public static boolean isSmtpConfigured(CompanySettings settings) {
        return notBlank(settings.getSmtpHost())
                && settings.getSmtpPort() != null
                && notBlank(settings.getSmtpUsername())
                && notBlank(settings.getSmtpFromAddress());
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
