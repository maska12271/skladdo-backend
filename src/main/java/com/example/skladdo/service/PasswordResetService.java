package com.example.skladdo.service;

import com.example.skladdo.dto.PasswordResetInfoResponse;
import com.example.skladdo.dto.SetupLinkResponse;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.PasswordResetToken;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.PasswordResetTokenRepository;
import com.example.skladdo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

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
    private final PasswordEncoder passwordEncoder;
    private final PlatformMailer mailer;

    private final String frontendBaseUrl;
    private final int expiryHours;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                PlatformMailer mailer,
                                @Value("${app.frontend-base-url}") String frontendBaseUrl,
                                @Value("${app.password-reset.expiry-hours:72}") int expiryHours) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
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
        return issueForUser(user, true);
    }

    /**
     * As above, but {@code sendEmail} decides whether the link is actually posted.
     *
     * <p>Creating a user mints the link without sending it: who delivers the invitation is the
     * administrator's call, not something to be decided for them. Some want it emailed from here, some
     * work in a company that filters unknown senders and would rather paste the link into a chat they know
     * their colleague reads. The link is returned either way; sending is a second, separate act.</p>
     */
    @Transactional
    public SetupLinkResponse issueForUser(User user, boolean sendEmail) {
        tokenRepository.deleteByUserId(user.getId());

        PasswordResetToken token = new PasswordResetToken();
        token.setToken(generateToken());
        token.setUser(user);
        token.setExpiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS));
        tokenRepository.save(token);

        String link = frontendBaseUrl + "/reset-password?token=" + token.getToken();

        // An account still awaiting its first password is being invited, not resetting one - the mail says
        // so, and the page the link opens does too.
        boolean invitation = Boolean.TRUE.equals(user.getPasswordSetupPending());

        // Queued rather than sent here: an SMTP conversation with an external host takes seconds, and
        // making an admin watch a spinner for it buys them nothing - the link is returned either way, and
        // a failure to send is logged rather than something they could act on mid-request. What comes back
        // is therefore "a sender is configured", not "it has arrived".
        boolean willSend = sendEmail && mailer.isConfigured();
        if (willSend) {
            mailer.sendPasswordLink(user, link, invitation, PlatformMailer.localeFor(user));
        }
        return new SetupLinkResponse(willSend, link, token.getExpiresAt());
    }

    /**
     * Drops every outstanding setup/reset link for an account. Called when a user is retired: an emailed
     * link is a way in, and it has to stop working the moment the account does.
     */
    @Transactional
    public void revokeTokensFor(Long userId) {
        tokenRepository.deleteByUserId(userId);
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
                .filter(u -> !Boolean.TRUE.equals(u.getArchived()) && !u.isDeleted())
                .orElseThrow(() -> new BadRequestException("error.auth.noAccount"));
        return issueForUser(user).emailSent();
    }

    /** Whether a token is still usable and, if so, which account it belongs to. */
    @Transactional(readOnly = true)
    public PasswordResetInfoResponse validate(String token) {
        return tokenRepository.findByToken(token)
                .filter(PasswordResetToken::isRedeemable)
                .map(t -> {
                    User user = t.getUser();
                    boolean invitation = Boolean.TRUE.equals(user.getPasswordSetupPending());
                    return new PasswordResetInfoResponse(true, user.getEmail(), invitation,
                            invitation && user.getCompany() != null ? user.getCompany().getName() : null);
                })
                .orElse(new PasswordResetInfoResponse(false, null, false, null));
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
    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
