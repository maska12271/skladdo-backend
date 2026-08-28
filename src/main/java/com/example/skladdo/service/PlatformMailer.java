package com.example.skladdo.service;

import com.example.skladdo.model.User;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sends Skladdo's own system mail — the password setup and reset links, and invitations to join a
 * company — from the platform's {@code noreply@} address ({@code app.mail.platform.*}).
 *
 * <p>Deliberately its own bean rather than a private method on {@link PasswordResetService}, for two
 * reasons. It is a different concern (talking SMTP, versus the lifecycle of a token), and {@code @Async}
 * only takes effect through the Spring proxy — a service calling its own async method just runs it inline
 * and the caller waits for the network anyway, which is the entire thing this exists to avoid.</p>
 *
 * <p>Manufacturer outreach is <em>not</em> sent from here: that goes out under each tenant's own SMTP
 * settings, because it is the customer's own correspondence rather than a message from Skladdo.</p>
 */
@Service
public class PlatformMailer {

    private static final Logger log = LoggerFactory.getLogger(PlatformMailer.class);

    private final CompanyMailSenderFactory mailSenderFactory;
    private final MessageSource messageSource;
    private final int expiryHours;

    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final boolean smtpUseTls;
    private final String fromAddress;
    private final String fromName;

    public PlatformMailer(CompanyMailSenderFactory mailSenderFactory,
                          MessageSource messageSource,
                          @Value("${app.password-reset.expiry-hours:72}") int expiryHours,
                          @Value("${app.mail.platform.smtp-host:}") String smtpHost,
                          @Value("${app.mail.platform.smtp-port:587}") int smtpPort,
                          @Value("${app.mail.platform.smtp-username:}") String smtpUsername,
                          @Value("${app.mail.platform.smtp-password:}") String smtpPassword,
                          @Value("${app.mail.platform.smtp-use-tls:true}") boolean smtpUseTls,
                          @Value("${app.mail.platform.from-address:noreply@skladdo.eu}") String fromAddress,
                          @Value("${app.mail.platform.from-name:Skladdo}") String fromName) {
        this.mailSenderFactory = mailSenderFactory;
        this.messageSource = messageSource;
        this.expiryHours = expiryHours;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername;
        this.smtpPassword = smtpPassword;
        this.smtpUseTls = smtpUseTls;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    /** Whether a sender is configured at all. Blank host = don't send, just hand back the link. */
    public boolean isConfigured() {
        return !smtpHost.isBlank();
    }

    /**
     * Warns at startup when the settings cannot work together, rather than letting every send fail.
     *
     * <p>The dev default is the Mailpit sink from docker-compose: {@code localhost:1025}, no auth, no
     * TLS. Pointing that at a real provider means changing the host - and the natural thing to override
     * is exactly the host, leaving a real server being dialled on Mailpit's port with encryption off. It
     * fails per-send, asynchronously, with nothing on screen: the UI has already said the message was
     * handed over, because from its side it was. That is a bad way to find out, and it is the shape of
     * mistake this catches.</p>
     *
     * <p>Only ever warns. A surprising combination is not necessarily a wrong one - somebody may really be
     * running a plaintext relay on a non-standard port - so refusing to start would be overreach.</p>
     */
    @jakarta.annotation.PostConstruct
    void warnIfMisconfigured() {
        if (!isConfigured()) {
            log.info("Platform mail is off (no app.mail.platform.smtp-host). Invitation and password "
                    + "links will be offered as copyable URLs instead of being emailed.");
            return;
        }
        boolean local = smtpHost.equalsIgnoreCase("localhost") || smtpHost.equals("127.0.0.1");
        if (!local && smtpPort == 1025) {
            log.warn("Platform mail points at '{}' on port 1025 - that is the local Mailpit sink's port, "
                    + "not a real SMTP one. Set app.mail.platform.smtp-port (587 for STARTTLS).", smtpHost);
        }
        if (!local && !smtpUseTls) {
            log.warn("Platform mail points at '{}' with TLS disabled. Real providers refuse plaintext "
                    + "logins; set app.mail.platform.smtp-use-tls=true.", smtpHost);
        }
        if (!local && smtpUsername.isBlank()) {
            log.warn("Platform mail points at '{}' with no username, so it will connect without "
                    + "authenticating. Set app.mail.platform.smtp-username if the server expects a login.",
                    smtpHost);
        }
        log.info("Platform mail: {}:{} (TLS {}), from {}", smtpHost, smtpPort,
                smtpUseTls ? "on" : "off", fromAddress);
    }

    /**
     * Opens one SMTP connection at startup purely to find out whether the credentials work.
     *
     * <p>Sending is asynchronous and failures are per-message warnings, which is right for a network blip
     * but wrong for a wrong password: that fails identically every time, and the only visible signal is a
     * log line nobody is watching. The admin UI cannot help either - it reports that a sender is
     * configured, which is true, and it cannot wait on the send to say more without making every
     * invitation block on an SMTP round trip.</p>
     *
     * <p>So the question gets asked once, up front, where the answer is actionable. Never throws: an
     * unreachable mail server is a reason to warn, not a reason to refuse to run.</p>
     *
     * <p>Driven by {@link ApplicationReadyEvent} rather than called from {@link #warnIfMisconfigured()},
     * and that is not a style choice. {@code @Async} works through the Spring proxy, so a bean calling
     * this on itself gets it <em>synchronously</em> - the SMTP round trip then lands on the boot thread
     * and startup waits for it (measured: ~8s against a reachable host, and a full 10s connect timeout
     * against an unreachable one, on every deploy). An event listener is invoked by the framework, so the
     * proxy applies and the probe genuinely runs on the mail pool, after the app is already serving.</p>
     */
    @Async("mailExecutor")
    @EventListener(ApplicationReadyEvent.class)
    void verifyLogin() {
        try {
            mailSenderFactory.create(smtpHost, smtpPort, smtpUsername, smtpPassword, smtpUseTls)
                    .testConnection();
            log.info("Platform mail: credentials accepted by {}.", smtpHost);
        } catch (Exception e) {
            log.warn("Platform mail: {} REJECTED the login ({}). Invitations and password links will not "
                    + "be delivered - the app will still offer copyable links. Check "
                    + "app.mail.platform.smtp-username (for Fastmail this is the account username, not "
                    + "the address you sign in to the website with) and the app password.",
                    smtpHost, e.getMessage());
        }
    }

    /**
     * Emails a setup/reset link, off the caller's thread.
     *
     * <p>An SMTP conversation with an external host takes seconds; nobody should watch a spinner for it,
     * least of all an administrator creating a user, who is handed the same link in the response either
     * way. A failure is logged rather than raised — by the time it happens the request is long finished,
     * and the link is already in the admin's hands.</p>
     *
     * <p>{@code invitation} picks the wording: a brand-new colleague is being welcomed to a company, which
     * is a different message from "you asked to reset your password", even though the link behind both is
     * the same one.</p>
     *
     * <p>Everything needed is read from {@code user} before the pool picks it up — address, name and
     * company — so nothing here touches a Hibernate session that has since closed. {@code locale} is
     * resolved by the caller for the same reason it is passed rather than looked up: {@code
     * LocaleContextHolder} is bound to the request thread, and this does not run on one.</p>
     */
    @Async("mailExecutor")
    public void sendPasswordLink(User user, String link, boolean invitation, Locale locale) {
        if (!isConfigured()) {
            return;
        }

        String recipient = user.getEmail();
        String name = user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName() : recipient;
        String company = user.getCompany() != null ? user.getCompany().getName() : "";
        String prefix = invitation ? "email.invitation" : "email.passwordReset";

        try {
            JavaMailSenderImpl sender = mailSenderFactory.create(smtpHost, smtpPort, smtpUsername, smtpPassword, smtpUseTls);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (!fromName.isBlank()) {
                helper.setFrom(fromAddress, fromName);
            } else {
                helper.setFrom(fromAddress);
            }
            helper.setTo(recipient);
            helper.setSubject(msg(locale, prefix + ".subject", company));
            helper.setText(body(prefix, name, company, link, locale), true);
            sender.send(message);
        } catch (Exception e) {
            log.warn("Platform email to {} failed: {}", recipient, e.getMessage());
        }
    }

    /**
     * Emails an invitation link to someone who has no account yet, off the caller's thread like the rest.
     *
     * <p>Addressed by company rather than by name: nobody here knows the recipient's name - that is the
     * point of the link - so the mail leads with who invited them, which is the fact that makes an
     * unexpected email from an unknown product legible.</p>
     *
     * <p>{@code locale} is the inviting administrator's, since the invitee has no stored preference yet.</p>
     */
    @Async("mailExecutor")
    public void sendCompanyInvite(String recipient, String company, String link, int validHours, Locale locale) {
        if (!isConfigured()) {
            return;
        }
        try {
            JavaMailSenderImpl sender = mailSenderFactory.create(smtpHost, smtpPort, smtpUsername, smtpPassword, smtpUseTls);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (!fromName.isBlank()) {
                helper.setFrom(fromAddress, fromName);
            } else {
                helper.setFrom(fromAddress);
            }
            helper.setTo(recipient);
            helper.setSubject(msg(locale, "email.userInvite.subject", company));
            helper.setText(inviteBody(company, link, validHours, locale), true);
            sender.send(message);
        } catch (Exception e) {
            log.warn("Invitation email to {} failed: {}", recipient, e.getMessage());
        }
    }

    private String inviteBody(String company, String link, int validHours, Locale locale) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#0f172a;line-height:1.5\">"
                + "<p>" + msg(locale, "email.userInvite.greeting") + "</p>"
                + "<p>" + msg(locale, "email.userInvite.body", company) + "</p>"
                + "<p><a href=\"" + link + "\" style=\"display:inline-block;background:#0f766e;color:#ffffff;"
                + "padding:10px 20px;border-radius:8px;text-decoration:none\">" + msg(locale, "email.userInvite.cta") + "</a></p>"
                + "<p style=\"color:#64748b;font-size:13px\">" + msg(locale, "email.userInvite.expiry", validHours) + "</p>"
                + "<p style=\"color:#64748b;font-size:13px\">" + msg(locale, "email.userInvite.ignore") + "</p>"
                + "</div>";
    }

    private String body(String prefix, String name, String company, String link, Locale locale) {
        return "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#0f172a;line-height:1.5\">"
                + "<p>" + msg(locale, prefix + ".greeting", name) + "</p>"
                + "<p>" + msg(locale, prefix + ".body", company) + "</p>"
                + "<p><a href=\"" + link + "\" style=\"display:inline-block;background:#0f766e;color:#ffffff;"
                + "padding:10px 20px;border-radius:8px;text-decoration:none\">" + msg(locale, prefix + ".cta") + "</a></p>"
                + "<p style=\"color:#64748b;font-size:13px\">" + msg(locale, prefix + ".expiry", expiryHours) + "</p>"
                + "<p style=\"color:#64748b;font-size:13px\">" + msg(locale, prefix + ".ignore") + "</p>"
                + "</div>";
    }

    /**
     * The recipient's own language, falling back to the caller's {@code Accept-Language} for accounts that
     * predate the per-user setting (and for the public forgot-password flow, which has no caller locale of
     * its own beyond the request).
     *
     * <p>Must be called on the request thread, before the send is queued — {@code LocaleContextHolder} is
     * thread-bound, so reading it on the mail pool would find nothing.</p>
     */
    public static Locale localeFor(User user) {
        String language = user.getLanguage();
        return language != null && !language.isBlank()
                ? Locale.forLanguageTag(language)
                : LocaleContextHolder.getLocale();
    }

    private String msg(Locale locale, String key, Object... args) {
        Object[] formatArgs = (args == null || args.length == 0) ? null : args;
        return messageSource.getMessage(key, formatArgs, key, locale);
    }
}
