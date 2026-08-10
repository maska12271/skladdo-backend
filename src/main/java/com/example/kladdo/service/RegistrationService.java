package com.example.kladdo.service;

import com.example.kladdo.dto.LoginResponse;
import com.example.kladdo.dto.RegisterRequest;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.model.Company;
import com.example.kladdo.model.CompanyType;
import com.example.kladdo.model.PlanType;
import com.example.kladdo.model.Role;
import com.example.kladdo.model.User;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
    private final AuthService authService;

    public RegistrationService(CompanyRepository companyRepository,
                               UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               PlanService planService,
                               AuthService authService) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.planService = planService;
        this.authService = authService;
    }

    /** Creates the company + owner on the chosen plan and returns a signed-in session. */
    public LoginResponse register(RegisterRequest request) {
        CompanyType accountType = parseAccountType(request.accountType());
        PlanType plan = resolvePlan(accountType, request.plan());
        String email = request.email().trim();
        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("error.register.emailTaken");
        }

        Company company = new Company();
        company.setName(request.companyName().trim());
        company.setActive(true);
        company.setType(accountType);
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
        // (cross-bean call) which reads the tenant we just set.
        TenantContext.setCompanyId(company.getId());
        try {
            planService.startSubscription(plan);
        } finally {
            TenantContext.clear();
        }

        return authService.issueSession(owner.getId());
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
        try {
            return CompanyType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.register.invalidAccountType");
        }
    }
}
