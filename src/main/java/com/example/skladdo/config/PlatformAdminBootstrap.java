package com.example.skladdo.config;

import com.example.skladdo.dto.SetupLinkResponse;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanyType;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.service.PasswordResetService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciles who operates the platform against the {@code app.platform-admin-emails} property on every
 * startup.
 *
 * <p>Platform administration crosses every tenant, so it deliberately has <strong>no</strong> API that
 * grants it: an account cannot promote itself or anyone else, and a compromised company owner cannot
 * escalate into it. Deployment configuration is the only door, which puts the decision where the
 * deployment's secrets already live.</p>
 *
 * <p>Reconciliation runs in <em>both</em> directions - listed accounts are granted the flag, unlisted ones
 * have it removed. Granting alone would make the property an append-only list where deleting a line looked
 * like it revoked access but did not, which is the sort of gap nobody notices until it matters.</p>
 *
 * <p>An email in the property with no matching account is provisioned fresh - see {@link #provisionOperator} -
 * into a dedicated {@link CompanyType#PLATFORM} shell company with no catalogue, orders or billing of its
 * own. That is deliberately the cheapest way to an admin-only login: naming an address nobody has signed
 * up with yet is enough, and {@link #warnIfDualRole} is what catches the other case, where the address
 * already belongs to a real tenant.</p>
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than as a {@code CommandLineRunner}. Ordering matters
 * here - the accounts have to exist before they can be matched - and {@code DataInitializer} declares no
 * {@code @Order}, so any number this class picked would be racing an unordered runner. Waiting for
 * "application ready" is ordered by definition: every runner has finished by then.</p>
 */
@Component
public class PlatformAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    /** Name of the shell company that owns every operator login. Never shown to a customer. */
    private static final String PLATFORM_COMPANY_NAME = "Skladdo";

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetService passwordResetService;
    private final String configuredEmails;

    public PlatformAdminBootstrap(UserRepository userRepository,
                                  CompanyRepository companyRepository,
                                  PasswordEncoder passwordEncoder,
                                  PasswordResetService passwordResetService,
                                  @Value("${app.platform-admin-emails:}") String configuredEmails) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetService = passwordResetService;
        this.configuredEmails = configuredEmails;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            reconcile();
        } catch (Exception e) {
            // Never fail startup over this: the app is perfectly usable by its tenants without an operator
            // panel, and a boot loop would be a far worse outcome than a missing flag.
            log.warn("Could not reconcile the platform administrators: {}", e.getMessage());
        }
    }

    private void reconcile() {
        List<String> wanted = parse(configuredEmails);
        Set<String> wantedKeys = wanted.stream().map(PlatformAdminBootstrap::key).collect(Collectors.toSet());

        // Revoke first. If the same address were somehow both listed and stale, ending on the grant is the
        // safer order - and it keeps the two loops independent of each other.
        for (User current : userRepository.findByPlatformAdminTrue()) {
            if (!wantedKeys.contains(key(current.getEmail()))) {
                current.setPlatformAdmin(false);
                userRepository.save(current);
                log.info("Revoked platform administration from {} (no longer listed).", current.getEmail());
            }
        }

        for (String email : wanted) {
            User user = findAccount(email);
            if (user == null) {
                // Nothing else can create one: a platform account is not something anybody can sign up
                // for, so if the configuration names an operator who has no login yet, this is the only
                // place it can come from.
                user = provisionOperator(email);
                if (user == null) {
                    continue;
                }
            }
            if (!user.isPlatformAdmin()) {
                user.setPlatformAdmin(true);
                userRepository.save(user);
                log.info("Granted platform administration to {}.", user.getEmail());
            }
            remindIfPasswordNotSet(user);
            warnIfDualRole(user);
        }

        if (wanted.isEmpty()) {
            log.info("No platform administrators configured (app.platform-admin-emails is empty); "
                    + "the admin panel is unreachable.");
        }
    }

    /**
     * Re-issues the setup link for an operator who still has no password, on every startup until they do.
     *
     * <p>The link is only ever delivered by being printed here - a platform company has no SMTP and nobody
     * to mail - so a single one-shot link would mean that scrolling past it once locks the operator out
     * with no way back but editing the database. Reissuing costs nothing: the account cannot be used until
     * a password is set either way, and each new link supersedes the last.</p>
     *
     * <p>Only for operators in the platform company. Somebody whose login lives in a real customer company
     * has ordinary ways to reset it.</p>
     */
    private void remindIfPasswordNotSet(User user) {
        if (!Boolean.TRUE.equals(user.getPasswordSetupPending())
                || user.getCompany() == null || !user.getCompany().isPlatformCompany()) {
            return;
        }
        try {
            SetupLinkResponse invite = passwordResetService.issueForUser(user);
            log.warn("{} has not set a password yet. Set it here (valid until {}): {}",
                    user.getEmail(), invite.expiresAt(), invite.setupLink());
        } catch (Exception e) {
            log.warn("Could not re-issue the password-setup link for {}: {}", user.getEmail(), e.getMessage());
        }
    }

    /**
     * Flags an operator whose login is also a real tenant's account.
     *
     * <p>Nothing here refuses it - a solo operator bootstrapping their own product plausibly wants their
     * everyday login to double as the admin one, and that is exactly how local dev is set up (see the
     * comment on {@code app.platform-admin-emails} in application.properties). But a production deploy
     * generally wants the opposite: an admin-only login with no tenant of its own, so the panel is all it
     * ever sees. An account that also owns a company sees that company's pages ALONGSIDE the admin ones,
     * not only the admin ones - easy to set up once for convenience and then forget is not what got
     * deployed. Logged on every boot for as long as it stays true, rather than only the first time, since
     * nobody re-reads a startup log from three weeks ago.</p>
     */
    private void warnIfDualRole(User user) {
        Company company = user.getCompany();
        if (company == null || company.isPlatformCompany()) {
            return;
        }
        log.warn("{} is a platform administrator whose login also belongs to '{}' - it will see that "
                + "company's pages alongside the admin panel, not only the admin panel. For an admin-only "
                + "login, list an address with no company of its own (see deploy/.env.example).",
                user.getEmail(), company.getName());
    }

    /**
     * Creates an operator login inside Skladdo's own {@link CompanyType#PLATFORM} company, creating that
     * company too the first time. Returns {@code null} if it could not be done, having said why.
     *
     * <p>No password is set here, and none is ever chosen for somebody else - the account is created
     * "awaiting password setup" exactly as an invited user is, and the emailed link is <em>logged</em>
     * because a brand-new platform company has no SMTP configured and nobody to send it to but the person
     * reading the console. That link is the one moment this is worth watching the startup output for.</p>
     *
     * <p>The company is a shell: no catalogue, orders, tenders or billing, because
     * {@link CompanyType#PLATFORM} closes every module and carries the free {@code PlanType.PLATFORM}.</p>
     */
    private User provisionOperator(String email) {
        Company company = platformCompany();
        if (company == null) {
            return null;
        }
        User operator = new User();
        operator.setEmail(email);
        operator.setFullName("Platform operator");
        operator.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        operator.setPasswordSetupPending(true);
        operator.setRole(Role.OWNER);
        operator.setCompany(company);
        operator.setActive(true);
        operator.setArchived(false);
        User saved = userRepository.save(operator);
        log.info("Created platform operator account {} in '{}'.", email, company.getName());
        // The setup link is not issued here: remindIfPasswordNotSet does it for every operator who still
        // has no password, so a fresh account and a forgotten one take the same path and only one link is
        // ever live at a time.
        return saved;
    }

    /** Skladdo's own company, created once and reused by every operator listed in the property. */
    private Company platformCompany() {
        Company existing = companyRepository.findFirstByType(CompanyType.PLATFORM).orElse(null);
        if (existing != null) {
            return existing;
        }
        Company company = new Company();
        company.setName(PLATFORM_COMPANY_NAME);
        company.setType(CompanyType.PLATFORM);
        company.setActive(true);
        company.setCreatedAt(Instant.now());
        Company saved = companyRepository.save(company);
        log.info("Created the platform company '{}' (id {}).", saved.getName(), saved.getId());
        return saved;
    }

    /**
     * The account for a configured address. {@code findByEmail} is an exact match, but the property is
     * typed by a human and the stored address may carry different capitalisation, so a miss is retried
     * case-insensitively rather than silently reported as "no such account".
     */
    private User findAccount(String email) {
        return userRepository.findByEmail(email)
                .or(() -> userRepository.findByEmailIgnoreCase(email))
                .orElse(null);
    }

    /** Addresses are compared case-insensitively; the configured spelling is kept for the lookup itself. */
    private static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(email -> !email.isEmpty())
                .toList();
    }

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
