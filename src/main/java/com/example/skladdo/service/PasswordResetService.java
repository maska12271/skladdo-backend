package com.example.skladdo.service;

import com.example.skladdo.dto.PasswordResetInfoResponse;
import com.example.skladdo.dto.SetupLinkResponse;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.PasswordResetToken;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.PasswordResetTokenRepository;
import com.example.skladdo.repository.UserRepository;
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
 * Issues and redeems the single-use links that let a user set (or reset) their own password. A link is
 * emailed through the platform's own noreply@ sender ({@code app.mail.platform.*}) - unlike manufacturer
 * emails, this is a Skladdo-branded system email, not outreach that should appear to come from the
 * tenant's own address, so it doesn't depend on any per-company SMTP setup. When the platform sender
 * isn't configured the link is still returned so an admin can share it manually.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final CompanyMailSenderFactory mailSenderFactory;
    private final PasswordEncoder passwordEncoder;
    private final MessageSource messageSource;

    private final String frontendBaseUrl;
    private final int expiryHours;

    private final String platformSmtpHost;
    private final int platformSmtpPort;
    private final String platformSmtpUsername;
    private final String platformSmtpPassword;
    private final boolean platformSmtpUseTls;
    private final String platformFromAddress;
    private final String platformFromName;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                CompanyMailSenderFactory mailSenderFactory,
                                PasswordEncoder passwordEncoder,
                                MessageSource messageSource,
                                @Value("${app.frontend-base-url}") String frontendBaseUrl,
                                @Value("${app.password-reset.expiry-hours:72}") int expiryHours,
                                @Value("${app.mail.platform.smtp-host:}") String platformSmtpHost,
                                @Value("${app.mail.platform.smtp-port:587}") int platformSmtpPort,
                                @Value("${app.mail.platform.smtp-username:}") String platformSmtpUsername,
                                @Value("${app.mail.platform.smtp-password:}") String platformSmtpPassword,
                                @Value("${app.mail.platform.smtp-use-tls:true}") boolean platformSmtpUseTls,
                                @Value("${app.mail.platform.from-address:noreply@skladdo.eu}") String platformFromAddress,
                                @Value("${app.mail.platform.from-name:Skladdo}") String platformFromName) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.mailSenderFactory = mailSenderFactory;
        this.passwordEncoder = passwordEncoder;
        this.messageSource = messageSource;
        this.frontendBaseUrl = stripTrailingSlash(frontendBaseUrl);
        this.expiryHours = expiryHours;
        this.platformSmtpHost = platformSmtpHost;
        this.platformSmtpPort = platformSmtpPort;
        this.platformSmtpUsername = platformSmtpUsername;
        this.platformSmtpPassword = platformSmtpPassword;
        this.platformSmtpUseTls = platformSmtpUseTls;
        this.platformFromAddress = platformFromAddress;
        this.platformFromName = platformFromName;
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
        User user = userRepository.findByEmailIgnoreCase(email.trim())
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
     * Sends the reset link through the platform's own noreply@ sender. Returns {@code false} (never
     * throws) when the platform sender isn't configured or the send fails, so onboarding still works via
     * the copyable link.
     */
    private boolean sendEmail(User user, String link) {
        if (platformSmtpHost.isBlank()) {
            return false;
        }

        try {
            JavaMailSenderImpl sender = mailSenderFactory.create(
                    platformSmtpHost, platformSmtpPort, platformSmtpUsername, platformSmtpPassword, platformSmtpUseTls);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (!platformFromName.isBlank()) {
                helper.setFrom(platformFromAddress, platformFromName);
            } else {
                helper.setFrom(platformFromAddress);
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
