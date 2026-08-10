package com.example.kladdo.service;

import com.example.kladdo.dto.SubscriptionViewDto;
import com.example.kladdo.exception.BadRequestException;
import com.example.kladdo.exception.ForbiddenException;
import com.example.kladdo.model.AddonType;
import com.example.kladdo.model.CompanyAddon;
import com.example.kladdo.model.CompanySubscription;
import com.example.kladdo.model.PlanType;
import com.example.kladdo.model.SubscriptionStatus;
import com.example.kladdo.repository.CompanyAddonRepository;
import com.example.kladdo.repository.CompanySubscriptionRepository;
import com.example.kladdo.repository.ManufacturerRepository;
import com.example.kladdo.repository.ProductRepository;
import com.example.kladdo.repository.UserRepository;
import com.example.kladdo.security.CustomUserDetails;
import com.example.kladdo.security.SecurityUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The company's subscription: reads and mutates the {@link CompanySubscription} and its {@link CompanyAddon}
 * rows, enforces the current {@link PlanType}'s caps at create time, and gates the add-on features. The
 * subscription row is created lazily with a Starter trial on first access (mirroring
 * {@link CompanySettingsService}), so existing companies work without a migration.
 *
 * <p>Billing is stubbed: there is no payment provider, so a plan/add-on change takes effect immediately.
 * The period-end rollover is applied lazily on read <em>and</em> by a nightly sweep
 * ({@code ScheduledMaintenanceService}) so periods still advance for a company nobody opens. Wiring a real
 * gateway would slot in around {@link #changePlan}/{@link #activateAddon} and the rollover helpers.</p>
 */
@Service
public class PlanService {

    /** Display currency for plan/add-on prices (billing is not live, so this is informational). */
    private static final String CURRENCY = "EUR";

    /** The plan a company falls back to when a subscription is created lazily (legacy companies). */
    private static final PlanType DEFAULT_PLAN = PlanType.STARTER;

    private final CompanySubscriptionRepository subscriptionRepository;
    private final CompanyAddonRepository addonRepository;
    private final UserRepository userRepository;
    private final ManufacturerRepository manufacturerRepository;
    private final ProductRepository productRepository;

    public PlanService(CompanySubscriptionRepository subscriptionRepository,
                       CompanyAddonRepository addonRepository,
                       UserRepository userRepository,
                       ManufacturerRepository manufacturerRepository,
                       ProductRepository productRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.addonRepository = addonRepository;
        this.userRepository = userRepository;
        this.manufacturerRepository = manufacturerRepository;
        this.productRepository = productRepository;
    }

    // --- Subscription lifecycle ------------------------------------------------------------------

    /** The company's subscription, creating a default one if none exists, then applying any rollover. */
    @Transactional
    public CompanySubscription getOrCreateSubscription() {
        CompanySubscription sub = subscriptionRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> createSubscription(lazyDefaultPlan()));
        return applyRollover(sub);
    }

    /**
     * The plan a company that predates this feature lands on when its row is created on first read. A
     * warehouse account gets the free {@link PlanType#WAREHOUSE} plan rather than {@link #DEFAULT_PLAN}:
     * it was never sold anything, so quietly enrolling it on a paid tier would contradict the "no payment"
     * promise its signup makes and put a price on the billing tab it never agreed to.
     */
    private static PlanType lazyDefaultPlan() {
        return warehouseAccountAtHome() ? PlanType.WAREHOUSE : DEFAULT_PLAN;
    }

    /**
     * True when this request belongs to a warehouse account working in its <em>own</em> tenant. Read off
     * the principal rather than the database (the account type is already carried there) and tolerant of
     * an absent security context, since the nightly rollover sweep runs unauthenticated.
     */
    private static boolean warehouseAccountAtHome() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof CustomUserDetails details
                && details.isWarehouseAccountAtHome();
    }

    /**
     * Starts a company's subscription on {@code plan}, effective immediately. There is no free trial:
     * the first billing period runs for one month, so {@link CompanySubscription#getCurrentPeriodEnd()}
     * is both the first-payment date and the point until which the owner may still cancel free of charge.
     * Called once at signup (the tenant must already be bound). No-op if a subscription already exists.
     */
    @Transactional
    public void startSubscription(PlanType plan) {
        if (subscriptionRepository.findFirstByOrderByIdAsc().isEmpty()) {
            createSubscription(plan);
        }
    }

    private CompanySubscription createSubscription(PlanType plan) {
        Instant now = Instant.now();
        CompanySubscription sub = new CompanySubscription();
        sub.setPlan(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCreatedAt(now);
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(plusOneMonth(now)); // first payment date; cancellable until then
        return subscriptionRepository.save(sub);
    }

    /**
     * Applies any due period rollover to the current tenant's <em>existing</em> subscription and add-ons.
     * Unlike {@link #getOrCreateSubscription()} it never creates a subscription, so the nightly sweep over
     * every company (see {@code ScheduledMaintenanceService}) does not silently enrol companies that have
     * none. Returns {@code true} when something was actually rolled over.
     */
    @Transactional
    public boolean runPeriodRollover() {
        boolean changed = false;
        CompanySubscription sub = subscriptionRepository.findFirstByOrderByIdAsc().orElse(null);
        if (sub != null && periodEnded(sub.getCurrentPeriodEnd())) {
            applyRollover(sub);
            changed = true;
        }
        for (CompanyAddon addon : addonRepository.findAll()) {
            if (periodEnded(addon.getCurrentPeriodEnd())) {
                rolloverAddon(addon);
                changed = true;
            }
        }
        return changed;
    }

    /** Whether a billing period with this end instant has run out (a null end never expires). */
    private static boolean periodEnded(Instant end) {
        return end != null && !Instant.now().isBefore(end);
    }

    /** Lazily advances an expired period: an unrenewed cancellation lapses (EXPIRED), otherwise it renews. */
    private CompanySubscription applyRollover(CompanySubscription sub) {
        if (!periodEnded(sub.getCurrentPeriodEnd())) {
            return sub;
        }
        Instant now = Instant.now();
        if (sub.isCancelAtPeriodEnd()) {
            // No perpetual free tier to fall back to: the subscription simply lapses. The last plan is
            // kept only so its caps still resolve; access should be treated as ended by the caller.
            sub.setStatus(SubscriptionStatus.EXPIRED);
            sub.setCancelAtPeriodEnd(false);
            sub.setTrialEndsAt(null);
            sub.setCurrentPeriodStart(now);
            sub.setCurrentPeriodEnd(null);
        } else {
            // The plan renews for another period; the payment for the period just ended happens here.
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setTrialEndsAt(null);
            sub.setCurrentPeriodStart(now);
            sub.setCurrentPeriodEnd(plusOneMonth(now));
        }
        return subscriptionRepository.save(sub);
    }

    /**
     * Switches the base plan, effective immediately. A manual switch is billed now (no new trial).
     *
     * <p>Both ends are checked against {@link PlanType#isSelectable()}. Moving <em>to</em> the warehouse
     * plan would let any business put itself on a free tier; moving <em>off</em> it would let a warehouse
     * account buy caps it has nothing to spend them on. That plan follows from the immutable account type,
     * so neither is a legitimate request however the UI is driven.</p>
     */
    @Transactional
    public void changePlan(PlanType plan) {
        CompanySubscription sub = getOrCreateSubscription();
        if (!plan.isSelectable() || !sub.getPlan().isSelectable()) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
        Instant now = Instant.now();
        sub.setPlan(plan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setTrialEndsAt(null);
        sub.setCancelAtPeriodEnd(false);
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(plusOneMonth(now));
        subscriptionRepository.save(sub);
    }

    /** Flags the plan to lapse at the period end (the subscription expires). Access continues until then. */
    @Transactional
    public void cancel() {
        CompanySubscription sub = getOrCreateSubscription();
        sub.setCancelAtPeriodEnd(true);
        subscriptionRepository.save(sub);
    }

    /** Undoes a pending cancellation so the plan renews as normal. */
    @Transactional
    public void resume() {
        CompanySubscription sub = getOrCreateSubscription();
        sub.setCancelAtPeriodEnd(false);
        subscriptionRepository.save(sub);
    }

    // --- Add-ons ---------------------------------------------------------------------------------

    /** Switches an add-on on (or clears a pending cancellation on one already active). */
    @Transactional
    public void activateAddon(AddonType type) {
        Instant now = Instant.now();
        CompanyAddon addon = addonRepository.findByAddonType(type).orElseGet(CompanyAddon::new);
        addon.setAddonType(type);
        addon.setCancelAtPeriodEnd(false);
        if (addon.getActivatedAt() == null) {
            addon.setActivatedAt(now);
        }
        if (addon.getCurrentPeriodEnd() == null || now.isAfter(addon.getCurrentPeriodEnd())) {
            addon.setCurrentPeriodEnd(plusOneMonth(now));
        }
        addonRepository.save(addon);
    }

    /** Flags an add-on to lapse at its period end (access continues until then). */
    @Transactional
    public void cancelAddon(AddonType type) {
        addonRepository.findByAddonType(type).ifPresent(addon -> {
            addon.setCancelAtPeriodEnd(true);
            addonRepository.save(addon);
        });
    }

    /** The set of currently-active add-ons, applying the same lazy period rollover as the subscription. */
    @Transactional
    public Set<AddonType> activeAddons() {
        Set<AddonType> active = EnumSet.noneOf(AddonType.class);
        for (CompanyAddon addon : addonRepository.findAll()) {
            if (rolloverAddon(addon)) {
                active.add(addon.getAddonType());
            }
        }
        return active;
    }

    /** @return true if the add-on is still active after applying any lapse at period end. */
    private boolean rolloverAddon(CompanyAddon addon) {
        if (!periodEnded(addon.getCurrentPeriodEnd())) {
            return true;
        }
        if (addon.isCancelAtPeriodEnd()) {
            addonRepository.delete(addon);
            return false;
        }
        addon.setCurrentPeriodEnd(plusOneMonth(Instant.now()));
        addonRepository.save(addon);
        return true;
    }

    public boolean hasAddon(AddonType type) {
        return activeAddons().contains(type);
    }

    // --- Enforcement (called at the top of the relevant create flows) ----------------------------

    public void assertCanCreateUser() {
        checkLimit(getOrCreateSubscription().getPlan().getMaxUsers(),
                userRepository.countByCompanyId(SecurityUtil.currentCompanyId()),
                "error.plan.userLimit");
    }

    public void assertCanCreateManufacturer() {
        checkLimit(getOrCreateSubscription().getPlan().getMaxManufacturers(),
                manufacturerRepository.count(),
                "error.plan.manufacturerLimit");
    }

    public void assertCanCreateProduct() {
        checkLimit(getOrCreateSubscription().getPlan().getMaxProducts(),
                productRepository.count(),
                "error.plan.productLimit");
    }

    public void assertTendersEnabled() {
        if (!hasAddon(AddonType.TENDERS)) {
            throw new ForbiddenException("error.plan.tendersLocked");
        }
    }

    public void assertEmailsEnabled() {
        if (!hasAddon(AddonType.MANUFACTURER_EMAILS)) {
            throw new ForbiddenException("error.plan.emailsLocked");
        }
    }

    private void checkLimit(int limit, long current, String messageKey) {
        if (limit != PlanType.UNLIMITED && current >= limit) {
            throw new ForbiddenException(messageKey, limit);
        }
    }

    // --- View (for the settings page) ------------------------------------------------------------

    @Transactional
    public SubscriptionViewDto view() {
        CompanySubscription sub = getOrCreateSubscription();
        Set<AddonType> active = activeAddons();
        PlanType plan = sub.getPlan();
        Long companyId = SecurityUtil.currentCompanyId();

        // A warehouse account is on the free plan and has nothing to buy: no other plan it may switch to,
        // and the add-ons (tenders, manufacturer emails) all belong to selling goods it never sells. Its
        // catalogue counts are structurally zero too, so only the user count says anything. Reporting that
        // as empty lists keeps the billing tab honest without it having to know about account types.
        boolean freePlan = !plan.isSelectable();

        List<SubscriptionViewDto.UsageItem> usage = freePlan
                ? List.of(new SubscriptionViewDto.UsageItem("USERS",
                        userRepository.countByCompanyId(companyId), plan.getMaxUsers()))
                : List.of(
                        new SubscriptionViewDto.UsageItem("USERS", userRepository.countByCompanyId(companyId), plan.getMaxUsers()),
                        new SubscriptionViewDto.UsageItem("MANUFACTURERS", manufacturerRepository.count(), plan.getMaxManufacturers()),
                        new SubscriptionViewDto.UsageItem("PRODUCTS", productRepository.count(), plan.getMaxProducts())
                );

        List<SubscriptionViewDto.PlanOption> plans = freePlan ? List.of() : Arrays.stream(PlanType.values())
                .filter(PlanType::isSelectable)
                .map(p -> new SubscriptionViewDto.PlanOption(p.name(), p.getMonthlyPrice(),
                        p.getMaxUsers(), p.getMaxManufacturers(), p.getMaxProducts()))
                .toList();

        List<SubscriptionViewDto.AddonOption> addons = new ArrayList<>();
        if (!freePlan) {
            for (AddonType type : AddonType.values()) {
                CompanyAddon row = addonRepository.findByAddonType(type).orElse(null);
                addons.add(new SubscriptionViewDto.AddonOption(
                        type.name(), type.getMonthlyPrice(), active.contains(type),
                        row != null && row.isCancelAtPeriodEnd(),
                        row != null ? row.getCurrentPeriodEnd() : null));
            }
        }

        return new SubscriptionViewDto(
                plan.name(), sub.getStatus().name(), sub.getTrialEndsAt(),
                sub.getCurrentPeriodEnd(), sub.isCancelAtPeriodEnd(),
                plan.getMonthlyPrice(), CURRENCY, usage, plans, addons);
    }

    private static Instant plusOneMonth(Instant from) {
        return from.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
    }
}
