package com.example.kladdo.service;

import com.example.kladdo.dto.PasswordResetInfoResponse;
import com.example.kladdo.dto.SetupLinkResponse;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.model.CompanySettings;
import com.example.kladdo.model.PasswordResetToken;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.CompanySettingsRepository;
import com.example.kladdo.repository.PasswordResetTokenRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.EncryptionService;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;

/**
 * Issues and redeems the single-use links that let a user set (or reset) their own password. A link
 * is emailed through the user's own company SMTP (the same per-tenant configuration the manufacturer
 * emails use); when that isn't configured the link is still returned so an admin can share it manually.
 *
 * <p>The public "forgot password" path has no tenant bound, so the company's SMTP settings are read by
 * id through a native query that bypasses the {@code @TenantId} filter (see
 * {@code CompanySettingsRepository.findByCompanyIdIgnoringTenant}).</p>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final CompanySettingsRepository companySettingsRepository;
    private final EncryptionService encryptionService;
    private final CompanyMailSenderFactory mailSenderFactory;
    private final PasswordEncoder passwordEncoder;
    private final MessageSource messageSource;

    private final String frontendBaseUrl;
    private final int expiryHours;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                CompanySettingsRepository companySettingsRepository,
                                EncryptionService encryptionService,
                                CompanyMailSenderFactory mailSenderFactory,
                                PasswordEncoder passwordEncoder,
                                MessageSource messageSource,
                                @Value("${app.frontend-base-url}") String frontendBaseUrl,
                                @Value("${app.password-reset.expiry-hours:72}") int expiryHours) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.companySettingsRepository = companySettingsRepository;
        this.encryptionService = encryptionService;
        this.mailSenderFactory = mailSenderFactory;
        this.passwordEncoder = passwordEncoder;
        this.messageSource = messageSource;
        this.frontendBaseUrl = stripTrailingSlash(frontendBaseUrl);
        this.expiryHours = expiryHours;
    }

    /**
     * Issues a fresh setup/reset link for {@code user} (superseding any earlier unused one) and tries to
     * email it. Returns the link and whether the email went out, so callers can fall back to sharing the
     * link manually. Never flips the user's {@code passwordSetupPending} flag.
     */
    @Transactional
    public SetupLinkResponse issueForUser(User user) {
        tokenRepository.deleteByUserId(user.getId());

        PasswordResetToken token = new PasswordResetToken();
        token.setToken(generateToken());
        token.setUser(user);
        token.setExpiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS));
        tokenRepository.save(token);

        String link = frontendBaseUrl + "/reset-password?token=" + token.getToken();
        boolean emailSent = sendEmail(user, link);
        return new SetupLinkResponse(emailSent, link, token.getExpiresAt());
    }

    /**
     * Public "forgot password" entry point. Fails fast with {@code error.auth.noAccount} for an unknown
     * or archived email so the reset page can tell the user immediately that no such account exists.
     *
     * <p>Returns whether the mail actually left, so the page can avoid promising an inbox that will never
     * receive anything. The link itself is never returned here - this endpoint is unauthenticated.</p>
     */
    @Transactional
    public boolean requestReset(String email) {
        User user = userRepository.findByEmail(email)
                .filter(u -> !Boolean.TRUE.equals(u.getArchived()))
                .orElseThrow(() -> new BadRequestException("error.auth.noAccount"));
        return issueForUser(user).emailSent();
    }

    /** Whether a token is still usable and, if so, which account it belongs to. */
    @Transactional(readOnly = true)
    public PasswordResetInfoResponse validate(String token) {
        return tokenRepository.findByToken(token)
                .filter(PasswordResetToken::isRedeemable)
                .map(t -> new PasswordResetInfoResponse(true, t.getUser().getEmail()))
                .orElse(new PasswordResetInfoResponse(false, null));
    }

    /** Redeems a token: sets the user's password, clears the pending flag, and marks the token used. */
    @Transactional
    public void reset(String token, String rawPassword) {
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .filter(PasswordResetToken::isRedeemable)
                .orElseThrow(() -> new BadRequestException("error.password.tokenInvalid"));

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setPasswordSetupPending(false);
        user.setActive(true);
        userRepository.save(user);

        resetToken.setUsedAt(Instant.now());
        tokenRepository.save(resetToken);
    }

    /**
     * Sends the reset link through the user's company SMTP. Returns {@code false} (never throws) when the
     * company has no usable SMTP configuration or the send fails, so onboarding still works via the
     * copyable link.
     */
    private boolean sendEmail(User user, String link) {
        // Read the target company's settings directly by id (bypassing the @TenantId filter) so this works
        // in the public "forgot password" flow, which runs with no company bound to the request.
        CompanySettings settings = companySettingsRepository
                .findByCompanyIdIgnoringTenant(user.getCompany().getId())
                .orElse(null);
        if (settings == null || !EmailSendingService.isSmtpConfigured(settings)) {
            return false;
        }

        try {
            String password = settings.getSmtpPasswordEncrypted() != null && !settings.getSmtpPasswordEncrypted().isBlank()
                    ? encryptionService.decrypt(settings.getSmtpPasswordEncrypted())
                    : null;
            JavaMailSenderImpl sender = mailSenderFactory.create(settings, password);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (settings.getSmtpFromName() != null && !settings.getSmtpFromName().isBlank()) {
                helper.setFrom(settings.getSmtpFromAddress(), settings.getSmtpFromName());
            } else {
                helper.setFrom(settings.getSmtpFromAddress());
            }
            // Write in the recipient's language, not whichever one the admin happens to be using.
            Locale locale = localeFor(user);
            helper.setTo(user.getEmail());
            helper.setSubject(msg(locale, "email.passwordReset.subject"));
            helper.setText(buildBody(user, link, locale), true);
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Password reset email to {} failed: {}", user.getEmail(), e.getMessage());
            return false;
        }
    }

    private String buildBody(User user, String link, Locale locale) {
        String name = user.getFullName() != null && !user.getFullName().isBlank()
                ? user.getFullName() : user.getEmail();
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#0f172a;line-height:1.5\">"
                + "<p>" + msg(locale, "email.passwordReset.greeting", name) + "</p>"
                + "<p>" + msg(locale, "email.passwordReset.body") + "</p>"
                + "<p><a href=\"" + link + "\" style=\"display:inline-block;background:#0f766e;color:#ffffff;"
                + "padding:10px 20px;border-radius:8px;text-decoration:none\">" + msg(locale, "email.passwordReset.cta") + "</a></p>"
                + "<p style=\"color:#64748b;font-size:13px\">" + msg(locale, "email.passwordReset.expiry", expiryHours) + "</p>"
                + "<p style=\"color:#64748b;font-size:13px\">" + msg(locale, "email.passwordReset.ignore") + "</p>"
                + "</div>";
    }

    /**
     * The recipient's own language, falling back to the caller's {@code Accept-Language} for accounts that
     * predate the per-user setting (and for the public forgot-password flow, which has no caller locale of
     * its own beyond the request).
     */
    private static Locale localeFor(User user) {
        String language = user.getLanguage();
        return language != null && !language.isBlank()
                ? Locale.forLanguageTag(language)
                : LocaleContextHolder.getLocale();
    }

    /** Resolves an i18n message at the given locale. */
    private String msg(Locale locale, String key, Object... args) {
        Object[] formatArgs = (args == null || args.length == 0) ? null : args;
        return messageSource.getMessage(key, formatArgs, key, locale);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
