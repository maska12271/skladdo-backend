package com.example.skladdo.service;

import com.example.skladdo.dto.LoginResponse;
import com.example.skladdo.dto.RegisterRequest;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.AddonType;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanyType;
import com.example.skladdo.model.InviteLink;
import com.example.skladdo.model.PlanType;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.security.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Public self-service signup: provisions a brand-new company with an OWNER account, starts its
 * subscription on the chosen paid plan (no trial — first payment is one month out, cancellable until
 * then), then hands back a ready-to-use session so the owner is signed straight in.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional} at this level. The trial row is
 * {@code @TenantId}-scoped and the tenant discriminator is fixed when the Hibernate session opens, so
 * the trial must be created in its own session <em>after</em> the new company id is bound to
 * {@link TenantContext} — the same reason the public forgot-password flow avoids a spanning transaction.
 * {@link Company} and {@link User} are not tenant-scoped, so each is saved in its own transaction.</p>
 */
@Service
public class RegistrationService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlanService planService;
    private final TaxRateService taxRateService;
    private final AuthService authService;
    private final InviteLinkService inviteLinkService;

    public RegistrationService(CompanyRepository companyRepository,
                               UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               PlanService planService,
                               TaxRateService taxRateService,
                               AuthService authService,
                               InviteLinkService inviteLinkService) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.planService = planService;
        this.taxRateService = taxRateService;
        this.authService = authService;
        this.inviteLinkService = inviteLinkService;
    }

    /** Creates the company + owner on the chosen plan and returns a signed-in session. */
    public LoginResponse register(RegisterRequest request) {
        // An invite link's terms come from the stored row, never from the request - see RegisterRequest.
        // A code that was sent but has since been revoked, expired or used up is refused outright rather
        // than quietly downgraded to an ordinary paid signup: someone who followed an invitation was
        // promised something, and silently charging them instead is the wrong way to say no.
        InviteLink invite = null;
        if (request.inviteCode() != null && !request.inviteCode().isBlank()) {
            invite = inviteLinkService.findUsable(request.inviteCode());
            if (invite == null) {
                throw new BadRequestException("error.invite.notUsable");
            }
        }

        // A link's stored type is applied instead of the request's, so it does not pass through
        // parseAccountType - hence the explicit check. InviteLinkService refuses to store a non-selectable
        // type in the first place; this is the second lock, because a row written by an older build (or by
        // hand) must not be able to mint an account type nobody may sign up for.
        CompanyType accountType;
        if (invite != null && invite.getAccountType() != null) {
            accountType = invite.getAccountType();
            if (!accountType.isSelectableAtSignup()) {
                throw new BadRequestException("error.invite.notUsable");
            }
        } else {
            accountType = parseAccountType(request.accountType());
        }
        PlanType plan = invite != null && invite.getPlan() != null && accountType != CompanyType.WAREHOUSE
                ? invite.getPlan()
                : resolvePlan(accountType, request.plan());

        // Every rejection has to happen before the first save. Nothing here runs in one transaction - the
        // class javadoc explains why - so a throw after `companyRepository.save` leaves a real company and
        // owner behind that nobody asked for. An unknown add-on name is such a rejection: the form quoted a
        // monthly total, and provisioning a company on a different one is worse than refusing.
        Set<AddonType> addons = accountType == CompanyType.WAREHOUSE
                ? Set.of()
                : parseAddons(request.addons());

        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("error.register.emailTaken");
        }

        Company company = new Company();
        company.setName(request.companyName().trim());
        company.setActive(true);
        company.setCreatedAt(java.time.Instant.now());
        company.setType(accountType);
        if (invite != null) {
            company.setInviteLinkId(invite.getId());
            if (invite.getFreeDays() != null) {
                // Resolved to a concrete date now, so editing the link later never changes what an
                // existing customer was promised.
                company.setFreeUntil(java.time.Instant.now()
                        .plus(invite.getFreeDays(), java.time.temporal.ChronoUnit.DAYS));
                company.setFreeNote("Signed up through invite link " + invite.getCode()
                        + " (" + invite.getLabel() + ")");
            }
        }
        company = companyRepository.save(company);

        User owner = new User();
        owner.setEmail(email);
        owner.setFullName(request.fullName().trim());
        owner.setPasswordHash(passwordEncoder.encode(request.password()));
        owner.setPasswordSetupPending(false); // they set it here, so login is not blocked
        owner.setRole(Role.OWNER);
        owner.setCompany(company);
        owner.setActive(true);
        owner.setArchived(false);
        owner = userRepository.save(owner);

        // Start the subscription on the chosen plan for the new tenant. Bind the company so the
        // @TenantId-scoped subscription row is stamped with it; startSubscription runs in its own session
        // (cross-bean call) which reads the tenant we just set. The add-ons are stamped the same way.
        TenantContext.setCompanyId(company.getId());
        try {
            planService.startSubscription(plan);
            for (AddonType addon : addons) {
                planService.activateAddon(addon);
            }
            // A starting tax catalogue, stamped with the same bound tenant, so the company can price a
            // product and issue an invoice without first building one from nothing.
            taxRateService.seedDefaults();
        } finally {
            TenantContext.clear();
        }

        // Counted only once the company actually exists, so a signup that failed validation does not
        // burn one of a limited link's uses.
        if (invite != null) {
            inviteLinkService.markUsed(invite.getId());
        }

        return authService.issueSession(owner.getId());
    }

    /**
     * The add-ons named in the request, as enum values.
     *
     * <p>An unrecognised name is an error rather than something to skip: the signup form showed the visitor
     * a monthly total, and silently dropping one of the extras would provision a company that does not
     * match what they agreed to.</p>
     */
    private static Set<AddonType> parseAddons(List<String> requested) {
        Set<AddonType> addons = new LinkedHashSet<>();
        if (requested == null) {
            return addons;
        }
        for (String name : requested) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                addons.add(AddonType.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("error.plan.invalidAddon");
            }
        }
        return addons;
    }

    /**
     * The plan the new company starts on, decided by the account type rather than taken on trust. A
     * warehouse account is always on the free {@link PlanType#WAREHOUSE} plan - it is not sold anything
     * yet, which is why its signup asks for neither a plan nor a card. A business must name one of the
     * paid tiers; asking for the warehouse plan is refused rather than honoured, or any signup could help
     * itself to a free account by sending one extra field.
     */
    private static PlanType resolvePlan(CompanyType accountType, String requested) {
        if (accountType == CompanyType.WAREHOUSE) {
            return PlanType.WAREHOUSE;
        }
        if (requested == null || requested.isBlank()) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
        PlanType plan = parsePlan(requested);
        if (!plan.isSelectable()) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
        return plan;
    }

    private static PlanType parsePlan(String value) {
        try {
            return PlanType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
    }

    /**
     * The account type is immutable once the company exists, so this is the only chance to get it right -
     * an unrecognised value is rejected rather than quietly defaulted. Omitting it entirely is fine and
     * means an ordinary business.
     */
    private static CompanyType parseAccountType(String value) {
        if (value == null || value.isBlank()) {
            return CompanyType.BUSINESS;
        }
        CompanyType type;
        try {
            type = CompanyType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.register.invalidAccountType");
        }
        // PLATFORM is a real enum value, so it would otherwise parse happily and let anyone register
        // Skladdo's own operator shell by sending one extra field. It is refused exactly like an unknown
        // type: the public form has no business naming it.
        if (!type.isSelectableAtSignup()) {
            throw new BadRequestException("error.register.invalidAccountType");
        }
        return type;
    }
}
