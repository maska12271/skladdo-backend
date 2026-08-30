package com.example.skladdo.service;

import com.example.skladdo.dto.AdminCompanyDetailDto;
import com.example.skladdo.dto.AdminCompanyDto;
import com.example.skladdo.dto.AdminStatsDto;
import com.example.skladdo.dto.CreateCompanyRequest;
import com.example.skladdo.dto.CreatedCompanyResponse;
import com.example.skladdo.dto.SetSponsorshipRequest;
import com.example.skladdo.dto.SetupLinkResponse;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.exception.ForbiddenException;
import com.example.skladdo.exception.ResourceNotFoundException;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanyType;
import com.example.skladdo.model.PlanType;
import com.example.skladdo.model.Role;
import com.example.skladdo.model.SubscriptionStatus;
import com.example.skladdo.model.User;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.CompanySubscriptionRepository;
import com.example.skladdo.repository.UserRepository;
import com.example.skladdo.security.SecurityUtil;
import com.example.skladdo.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The platform operator's view of every tenant: how many companies and users exist, who signed up
 * recently, who has stopped using the app, and the handful of actions that manage a company from outside
 * it (provision one, suspend one).
 *
 * <p>This is the one service that deliberately works <em>across</em> tenants, so two things about it are
 * unusual and load-bearing:</p>
 *
 * <ul>
 *   <li>{@link Company} and {@link User} are not {@code @TenantId}-scoped (they must be readable before a
 *       tenant is known, at authentication time), so they can simply be queried. The subscription
 *       <em>is</em> scoped, which is why its rows come from
 *       {@link CompanySubscriptionRepository#findAllIgnoringTenant()} - a native query - rather than a
 *       repository call that would silently report one tenant's plan for every company.</li>
 *   <li>Filtering, sorting and paging the company list happen <strong>in memory</strong>. The list joins
 *       three sources that no single query spans (the company row, a tenant-scoped subscription, and
 *       per-company user aggregates), and the population is a few hundred rows at most. If this ever
 *       serves tens of thousands of companies, replace {@link #listCompanies} with a native join - the
 *       DTO and the endpoint contract do not need to change.</li>
 * </ul>
 *
 * <p>Every mutation is logged with the acting operator. A dedicated cross-tenant audit table is a later
 * step; the company's own {@code AuditLog} is the wrong home for "the platform suspended this company",
 * since that record belongs to no single tenant.</p>
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** Trailing windows the dashboard reports signups over. */
    private static final List<Integer> SIGNUP_WINDOWS = List.of(7, 30, 90);

    /** Trailing windows the dashboard reports usage over ("connected in the last month / three months"). */
    private static final List<Integer> ACTIVITY_WINDOWS = List.of(30, 90);

    /** How many recent signups the dashboard names inline. */
    private static final int RECENT_SIGNUP_COUNT = 5;

    /** How far ahead the dashboard warns about a free period running out. */
    private static final int SPONSORSHIP_WARNING_DAYS = 7;

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final CompanySubscriptionRepository subscriptionRepository;
    private final com.example.skladdo.repository.StoredFileRepository storedFileRepository;
    private final com.example.skladdo.repository.TenantFootprintRepository footprintRepository;
    private final PlanService planService;
    private final TaxRateService taxRateService;
    private final PasswordResetService passwordResetService;
    private final PasswordEncoder passwordEncoder;

    public AdminService(CompanyRepository companyRepository,
                        UserRepository userRepository,
                        CompanySubscriptionRepository subscriptionRepository,
                        com.example.skladdo.repository.StoredFileRepository storedFileRepository,
                        com.example.skladdo.repository.TenantFootprintRepository footprintRepository,
                        PlanService planService,
                        TaxRateService taxRateService,
                        PasswordResetService passwordResetService,
                        PasswordEncoder passwordEncoder) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.storedFileRepository = storedFileRepository;
        this.footprintRepository = footprintRepository;
        this.planService = planService;
        this.taxRateService = taxRateService;
        this.passwordResetService = passwordResetService;
        this.passwordEncoder = passwordEncoder;
    }

    // --- Dashboard ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminStatsDto stats() {
        List<AdminCompanyDto> all = allCompanies();
        Instant now = Instant.now();

        long business = all.stream().filter(c -> CompanyType.BUSINESS.name().equals(c.type())).count();
        long warehouse = all.stream().filter(c -> CompanyType.WAREHOUSE.name().equals(c.type())).count();
        long suspended = all.stream().filter(c -> Status.SUSPENDED.name().equals(c.status())).count();
        long overdue = all.stream().filter(c -> Status.OVERDUE.name().equals(c.status())).count();
        long sponsored = all.stream().filter(c -> Status.SPONSORED.name().equals(c.status())).count();

        // Only periods still running: one that already lapsed is not "ending soon", it has ended, and the
        // company has fallen back to its ordinary status where it will be chased normally.
        Instant soon = now.plus(SPONSORSHIP_WARNING_DAYS, ChronoUnit.DAYS);
        List<AdminCompanyDto> endingSoon = all.stream()
                .filter(c -> c.freeUntil() != null
                        && c.freeUntil().isAfter(now)
                        && c.freeUntil().isBefore(soon))
                .sorted(Comparator.comparing(AdminCompanyDto::freeUntil))
                .toList();

        List<AdminStatsDto.WindowCount> newCompanies = SIGNUP_WINDOWS.stream()
                .map(days -> {
                    Instant cutoff = now.minus(days, ChronoUnit.DAYS);
                    // A null creation date is never counted: those are companies that predate the column,
                    // and calling them new would overstate exactly the figure this exists to report.
                    long count = all.stream()
                            .filter(c -> c.createdAt() != null && c.createdAt().isAfter(cutoff))
                            .count();
                    return new AdminStatsDto.WindowCount(days, count);
                })
                .toList();

        List<AdminStatsDto.WindowCount> active = ACTIVITY_WINDOWS.stream()
                .map(days -> {
                    Instant cutoff = now.minus(days, ChronoUnit.DAYS);
                    long count = all.stream()
                            .filter(c -> c.lastActiveAt() != null && c.lastActiveAt().isAfter(cutoff))
                            .count();
                    return new AdminStatsDto.WindowCount(days, count);
                })
                .toList();

        Map<String, Long> byPlan = all.stream()
                .filter(c -> c.plan() != null)
                .collect(Collectors.groupingBy(AdminCompanyDto::plan, Collectors.counting()));
        List<AdminStatsDto.PlanCount> planMix = byPlan.entrySet().stream()
                .map(e -> new AdminStatsDto.PlanCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(AdminStatsDto.PlanCount::count).reversed())
                .toList();

        List<AdminCompanyDto> recent = all.stream()
                .filter(c -> c.createdAt() != null)
                .sorted(Comparator.comparing(AdminCompanyDto::createdAt).reversed())
                .limit(RECENT_SIGNUP_COUNT)
                .toList();

        return new AdminStatsDto(
                all.size(), business, warehouse, suspended, sponsored,
                userRepository.countCustomerSeats(),
                newCompanies, active, overdue, planMix, recent, endingSoon);
    }

    // --- Companies ---------------------------------------------------------------------------------

    /**
     * The company list, filtered and paged. Multi-value filters are OR-ed within a filter and AND-ed
     * across filters, matching every other list in the app.
     */
    @Transactional(readOnly = true)
    public Page<AdminCompanyDto> listCompanies(String search,
                                               List<String> types,
                                               List<String> statuses,
                                               List<String> plans,
                                               Pageable pageable) {
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        List<AdminCompanyDto> filtered = allCompanies().stream()
                .filter(c -> needle.isEmpty() || matches(c, needle))
                .filter(c -> types == null || types.isEmpty() || types.contains(c.type()))
                .filter(c -> statuses == null || statuses.isEmpty() || statuses.contains(c.status()))
                .filter(c -> plans == null || plans.isEmpty() || (c.plan() != null && plans.contains(c.plan())))
                .sorted(comparatorFor(pageable))
                .toList();

        int from = (int) Math.min(pageable.getOffset(), filtered.size());
        int to = Math.min(from + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(from, to), pageable, filtered.size());
    }

    private static boolean matches(AdminCompanyDto company, String needle) {
        return contains(company.name(), needle)
                || contains(company.registrationCode(), needle)
                || contains(company.ownerEmail(), needle)
                || contains(company.ownerName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Sorting for the list. Nulls always sort last regardless of direction: an unknown creation date or a
     * company nobody has ever signed in to is not "the oldest", and letting it head the list would bury
     * the rows the operator actually sorted for.
     */
    private static Comparator<AdminCompanyDto> comparatorFor(Pageable pageable) {
        String property = pageable.getSort().stream().findFirst()
                .map(order -> order.getProperty()).orElse("createdAt");
        boolean descending = pageable.getSort().stream().findFirst()
                .map(order -> order.isDescending()).orElse(true);

        Comparator<AdminCompanyDto> comparator = switch (property) {
            case "name" -> Comparator.comparing(AdminCompanyDto::name, String.CASE_INSENSITIVE_ORDER);
            case "userCount" -> Comparator.comparingLong(AdminCompanyDto::userCount);
            case "lastActiveAt" -> Comparator.comparing(AdminCompanyDto::lastActiveAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "plan" -> Comparator.comparing(AdminCompanyDto::plan,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "status" -> Comparator.comparing(AdminCompanyDto::status);
            default -> Comparator.comparing(AdminCompanyDto::createdAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        return descending ? comparator.reversed() : comparator;
    }

    @Transactional(readOnly = true)
    public AdminCompanyDetailDto companyDetail(Long id) {
        Company company = requireCompany(id);
        Map<Long, SubscriptionRow> subscriptions = subscriptionsByCompany();
        SubscriptionRow subscription = subscriptions.get(id);

        List<User> members = userRepository.findByCompanyId(id);
        User owner = members.stream().filter(u -> u.getRole() == Role.OWNER).findFirst().orElse(null);
        Instant lastActive = members.stream()
                .map(User::getLastLoginAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        long seats = members.stream().filter(u -> !Boolean.TRUE.equals(u.getArchived())).count();

        AdminCompanyDto summary = toDto(company, subscription, seats, lastActive, owner);

        List<AdminCompanyDetailDto.Member> users = members.stream()
                .sorted(Comparator.comparing(User::getId))
                .map(u -> new AdminCompanyDetailDto.Member(
                        u.getId(), u.getEmail(), u.getFullName(), u.getRole().name(),
                        u.getActive(), u.getArchived(), u.getPasswordSetupPending(), u.getLastLoginAt()))
                .toList();

        // What this customer actually costs to host, in the two places they take up room. Computed here
        // rather than in the list: the database figure scans every table for the company, which is fine
        // once for one company and not fine once per row of a page.
        List<Object[]> storage = storedFileRepository.sumSizeForCompanyIgnoringTenant(id);
        long storageBytes = storage.isEmpty() ? 0 : ((Number) storage.get(0)[0]).longValue();
        long storageFiles = storage.isEmpty() ? 0 : ((Number) storage.get(0)[1]).longValue();

        return new AdminCompanyDetailDto(
                summary,
                subscription == null ? null : subscription.periodStart(),
                subscription == null ? null : subscription.cancelAtPeriodEnd(),
                storageBytes,
                storageFiles,
                footprintRepository.rowBytesFor(id),
                users);
    }

    // --- Provisioning ------------------------------------------------------------------------------

    /**
     * Creates a company and its owner from the operator's side, then emails the owner a link to set their
     * own password.
     *
     * <p>Deliberately <em>not</em> {@code @Transactional}, for the reason {@code RegistrationService}
     * documents: the subscription row is {@code @TenantId}-scoped and Hibernate fixes the discriminator
     * when the session opens, so it has to be written in a fresh session with the new company bound.</p>
     */
    public CreatedCompanyResponse createCompany(CreateCompanyRequest request) {
        CompanyType accountType = parseAccountType(request.accountType());
        PlanType plan = resolvePlan(accountType, request.plan());

        String email = request.ownerEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("error.register.emailTaken");
        }
        String registrationCode = blankToNull(request.registrationCode());
        if (registrationCode != null
                && companyRepository.findByRegistrationCode(registrationCode).isPresent()) {
            throw new BadRequestException("error.company.registrationCodeExists");
        }

        Company company = new Company();
        company.setName(request.companyName().trim());
        company.setRegistrationCode(registrationCode);
        company.setActive(true);
        company.setCreatedAt(Instant.now());
        company.setType(accountType);
        Company saved = companyRepository.save(company);

        User owner = new User();
        owner.setEmail(email);
        owner.setFullName(request.ownerName().trim());
        // No password is ever chosen here: a random hash satisfies the NOT NULL column while being
        // impossible to guess, and the pending flag blocks sign-in until the owner sets their own through
        // the emailed link - the same shape as inviting a user.
        owner.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        owner.setPasswordSetupPending(true);
        owner.setRole(Role.OWNER);
        owner.setCompany(saved);
        owner.setActive(true);
        owner.setArchived(false);
        User savedOwner = userRepository.save(owner);

        TenantContext.callAs(saved.getId(), () -> {
            planService.startSubscription(plan);
            // A starting tax catalogue, so the new company can price and invoice without building one first.
            taxRateService.seedDefaults();
            return null;
        });

        SetupLinkResponse invite = passwordResetService.issueForUser(savedOwner);
        log.info("Operator {} provisioned company '{}' (id {}) on plan {} with owner {}.",
                actorEmail(), saved.getName(), saved.getId(), plan, savedOwner.getEmail());

        AdminCompanyDto dto = toDto(saved, subscriptionsByCompany().get(saved.getId()), 1, null, savedOwner);
        return new CreatedCompanyResponse(dto, invite.emailSent(), invite.setupLink(), invite.expiresAt());
    }

    /**
     * Grants or extends a company's free period.
     *
     * <p>By default the period <em>extends</em> from where it currently ends rather than from today, so
     * "give them another month" adds a month to what they have instead of quietly shortening it to 30
     * days from now. A lapsed period never extends - there is nothing left to add to - so that restarts
     * from today, as does an explicit {@code fromToday}.</p>
     */
    @Transactional
    public AdminCompanyDto setSponsorship(Long id, SetSponsorshipRequest request) {
        Company company = requireCompany(id);
        Instant now = Instant.now();
        boolean restart = Boolean.TRUE.equals(request.fromToday())
                || company.getFreeUntil() == null
                || company.getFreeUntil().isBefore(now);
        Instant from = restart ? now : company.getFreeUntil();

        company.setFreeUntil(from.plus(request.days(), ChronoUnit.DAYS));
        if (request.note() != null) {
            String note = request.note().trim();
            company.setFreeNote(note.isEmpty() ? null : note);
        }
        Company saved = companyRepository.save(company);
        log.info("Operator {} granted company '{}' (id {}) {} free day(s), now free until {}.",
                actorEmail(), saved.getName(), saved.getId(), request.days(), saved.getFreeUntil());
        return companyDetail(id).company();
    }

    /**
     * Ends a company's free period immediately. Separate from {@link #setSponsorship} on purpose: taking
     * away someone's free access should be its own deliberate action, not something a mistyped number in
     * the grant form can do.
     */
    @Transactional
    public AdminCompanyDto clearSponsorship(Long id) {
        Company company = requireCompany(id);
        company.setFreeUntil(null);
        company.setFreeNote(null);
        Company saved = companyRepository.save(company);
        log.info("Operator {} ended the free period for company '{}' (id {}).",
                actorEmail(), saved.getName(), saved.getId());
        return companyDetail(id).company();
    }

    /**
     * Suspends or restores a company. Suspension disables every account in it at authentication time (see
     * {@code CustomUserDetails.isEnabled()}), so it takes hold on the next request rather than whenever
     * the users' tokens happen to expire.
     *
     * <p>A company holding a platform operator's own account is refused. Suspending it would lock the
     * operator out of the panel that undoes the suspension, leaving the database as the only way back.</p>
     */
    @Transactional
    public AdminCompanyDto setSuspended(Long id, boolean suspended) {
        Company company = requireCompany(id);
        if (suspended && userRepository.existsByCompanyIdAndPlatformAdminTrue(id)) {
            throw new ForbiddenException("error.admin.cannotSuspendOperator");
        }
        company.setActive(!suspended);
        Company saved = companyRepository.save(company);
        log.info("Operator {} {} company '{}' (id {}).",
                actorEmail(), suspended ? "suspended" : "restored", saved.getName(), saved.getId());
        return companyDetail(id).company();
    }

    // --- Assembly ----------------------------------------------------------------------------------

    /**
     * Every company as a dashboard/list row, joining the three sources in bulk: the company rows, one
     * subscription query across all tenants, and per-company user aggregates. Three queries in total,
     * regardless of how many companies there are.
     */
    private List<AdminCompanyDto> allCompanies() {
        Map<Long, SubscriptionRow> subscriptions = subscriptionsByCompany();
        Map<Long, Long> seats = toLongMap(userRepository.countSeatsPerCompany());
        Map<Long, Instant> lastActive = new HashMap<>();
        for (Object[] row : userRepository.findLastLoginPerCompany()) {
            Instant when = toInstant(row[1]);
            if (row[0] != null && when != null) {
                lastActive.put(toLong(row[0]), when);
            }
        }
        Map<Long, User> owners = new HashMap<>();
        for (User owner : userRepository.findByRole(Role.OWNER)) {
            if (owner.getCompany() != null) {
                owners.putIfAbsent(owner.getCompany().getId(), owner);
            }
        }

        return companyRepository.findAll().stream()
                // Skladdo's own shell company is not a customer and must never be counted as one: it would
                // inflate the headline figure, appear in the list as something to sell to, and break the
                // "total = business + warehouse" arithmetic the dashboard states.
                .filter(company -> company.getType() != CompanyType.PLATFORM)
                .map(company -> toDto(company,
                        subscriptions.get(company.getId()),
                        seats.getOrDefault(company.getId(), 0L),
                        lastActive.get(company.getId()),
                        owners.get(company.getId())))
                .toList();
    }

    private static AdminCompanyDto toDto(Company company, SubscriptionRow subscription,
                                         long seats, Instant lastActive, User owner) {
        return new AdminCompanyDto(
                company.getId(),
                company.getName(),
                company.getRegistrationCode(),
                company.getType().name(),
                deriveStatus(company, subscription).name(),
                subscription == null ? null : subscription.plan(),
                subscription == null ? null : subscription.status(),
                subscription == null ? null : subscription.periodEnd(),
                seats,
                company.getCreatedAt(),
                lastActive,
                owner == null ? null : owner.getFullName(),
                owner == null ? null : owner.getEmail(),
                company.getFreeUntil(),
                company.getFreeNote(),
                company.getInviteLinkId());
    }

    /** The operational states the panel reports; see {@link #deriveStatus}. */
    private enum Status { SUSPENDED, SPONSORED, OVERDUE, ACTIVE }

    /**
     * What state a company is in, in precedence order.
     *
     * <p><strong>Suspended</strong> is a decision somebody made, so it outranks everything.
     * <strong>Sponsored</strong> comes next and is the reason it sits above overdue: a company on a free
     * period owes nothing, so letting a lapsed billing date paint it as overdue would put the operator's
     * own customers on their chase list. <strong>Overdue</strong> means a paying company's billing period
     * has lapsed - either it expired outright or its end date has passed without the rollover having
     * caught up. It is a <em>follow-up signal only</em>: nothing in the app is restricted by it,
     * deliberately, because there is no payment provider yet and no company has ever actually failed to
     * pay. Automatically cutting off access on the strength of a billing system that never charges anyone
     * would lock out paying customers over bookkeeping.</p>
     *
     * <p>Free plans are never overdue either: {@link PlanType#isSelectable()} is false only for the
     * warehouse tier, which costs nothing, so a lapsed period there means nothing is owed.</p>
     */
    private static Status deriveStatus(Company company, SubscriptionRow subscription) {
        if (!company.isActive()) {
            return Status.SUSPENDED;
        }
        if (company.isSponsored()) {
            return Status.SPONSORED;
        }
        if (subscription == null || isFreePlan(subscription.plan())) {
            return Status.ACTIVE;
        }
        boolean expired = SubscriptionStatus.EXPIRED.name().equals(subscription.status());
        boolean lapsed = subscription.periodEnd() != null && subscription.periodEnd().isBefore(Instant.now());
        return expired || lapsed ? Status.OVERDUE : Status.ACTIVE;
    }

    private static boolean isFreePlan(String plan) {
        if (plan == null) {
            return false;
        }
        try {
            return !PlanType.valueOf(plan).isSelectable();
        } catch (IllegalArgumentException e) {
            // A plan value this build no longer knows (an old row): treat it as payable rather than free,
            // so it surfaces for a human to look at instead of quietly disappearing from the overdue list.
            return false;
        }
    }

    /**
     * Every company's subscription, keyed by company. Native, because the entity is {@code @TenantId}-scoped
     * - see {@link CompanySubscriptionRepository#findAllIgnoringTenant()}.
     */
    private Map<Long, SubscriptionRow> subscriptionsByCompany() {
        Map<Long, SubscriptionRow> byCompany = new LinkedHashMap<>();
        for (Object[] row : subscriptionRepository.findAllIgnoringTenant()) {
            if (row[0] == null) {
                continue;
            }
            byCompany.put(toLong(row[0]), new SubscriptionRow(
                    asString(row[1]), asString(row[2]),
                    toInstant(row[3]), toInstant(row[4]),
                    Boolean.TRUE.equals(row[5])));
        }
        return byCompany;
    }

    /** One company's subscription, flattened out of the native query. */
    private record SubscriptionRow(String plan, String status, Instant periodStart, Instant periodEnd,
                                   boolean cancelAtPeriodEnd) {
    }

    // --- Plumbing ----------------------------------------------------------------------------------

    private Company requireCompany(Long id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + id));
    }

    private static String actorEmail() {
        try {
            return SecurityUtil.currentUser().getEmail();
        } catch (IllegalStateException e) {
            return "unknown";
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Mirrors {@code RegistrationService.resolvePlan}: the account type decides the plan rather than the
     * caller. A warehouse account is free and always on the non-selectable warehouse tier; a business must
     * name a paid one. Enforced here too because this endpoint creates companies just as the public signup
     * does, and the rule belongs to the data, not to the form in front of it.
     */
    private static PlanType resolvePlan(CompanyType accountType, String requested) {
        if (accountType == CompanyType.WAREHOUSE) {
            return PlanType.WAREHOUSE;
        }
        if (requested == null || requested.isBlank()) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
        PlanType plan;
        try {
            plan = PlanType.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
        if (!plan.isSelectable()) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
        return plan;
    }

    private static CompanyType parseAccountType(String value) {
        if (value == null || value.isBlank()) {
            return CompanyType.BUSINESS;
        }
        CompanyType type;
        try {
            type = CompanyType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.register.invalidAccountType");
        }
        // Refused here too, not just on the public form. A platform company is provisioned from the
        // deployment's configuration and nowhere else, so even the operator does not hand them out - a
        // second one created by accident would be a login nobody meant to exist.
        if (!type.isSelectableAtSignup()) {
            throw new BadRequestException("error.register.invalidAccountType");
        }
        return type;
    }

    // A native query returns whatever type Hibernate resolved for the unmapped column - Instant, Timestamp
    // and OffsetDateTime are all plausible depending on the column and driver. Converting defensively here
    // keeps that detail from reaching the rest of the service.

    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime offset -> offset.toInstant();
            case LocalDateTime local -> local.toInstant(ZoneOffset.UTC);
            default -> null;
        };
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<Long, Long> toLongMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            Long key = toLong(row[0]);
            Long count = toLong(row[1]);
            if (key != null && count != null) {
                map.put(key, count);
            }
        }
        return map;
    }
}
