package com.example.skladdo.service;

import com.example.skladdo.model.AddonType;
import com.example.skladdo.model.CompanyAddon;
import com.example.skladdo.model.CompanySubscription;
import com.example.skladdo.model.PlanType;
import com.example.skladdo.model.SubscriptionStatus;
import com.example.skladdo.repository.CompanyAddonRepository;
import com.example.skladdo.repository.CompanyRepository;
import com.example.skladdo.repository.CompanySubscriptionRepository;
import com.example.skladdo.repository.ManufacturerRepository;
import com.example.skladdo.repository.ProductRepository;
import com.example.skladdo.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlanService#runPeriodRollover()} - the billing-period advance the nightly
 * {@link ScheduledMaintenanceService} sweep applies to every company.
 *
 * <p>The job runs at 03:15, so this covers the behaviour that cannot be exercised through the API: most
 * importantly that a sweep over <em>all</em> companies never enrols one that has no subscription (unlike
 * {@code getOrCreateSubscription}, which creates on demand). Unlike the other tests here it uses mocks -
 * the logic is repository orchestration rather than a pure function, and Mockito already ships with the
 * Spring Boot test starters.</p>
 */
class PlanServiceRolloverTest {

    private final CompanySubscriptionRepository subscriptions = mock(CompanySubscriptionRepository.class);
    private final CompanyAddonRepository addons = mock(CompanyAddonRepository.class);

    private final PlanService service = new PlanService(
            subscriptions,
            addons,
            mock(UserRepository.class),
            mock(ManufacturerRepository.class),
            mock(ProductRepository.class),
            mock(CompanyRepository.class));

    private static Instant daysFromNow(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS);
    }

    private CompanySubscription subscription(Instant periodEnd, boolean cancelAtPeriodEnd) {
        CompanySubscription sub = new CompanySubscription();
        sub.setPlan(PlanType.STARTER);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        when(subscriptions.findFirstByOrderByIdAsc()).thenReturn(Optional.of(sub));
        when(subscriptions.save(any(CompanySubscription.class))).thenAnswer(call -> call.getArgument(0));
        return sub;
    }

    private CompanyAddon addon(Instant periodEnd, boolean cancelAtPeriodEnd) {
        CompanyAddon addon = new CompanyAddon();
        addon.setAddonType(AddonType.TENDERS);
        addon.setCurrentPeriodEnd(periodEnd);
        addon.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        when(addons.findAll()).thenReturn(List.of(addon));
        return addon;
    }

    /** The sweep visits every company, so it must not create a subscription for one that has none. */
    @Test
    void neverCreatesASubscriptionForACompanyWithoutOne() {
        when(subscriptions.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(addons.findAll()).thenReturn(List.of());

        assertFalse(service.runPeriodRollover(), "nothing to roll over");
        verify(subscriptions, never()).save(any());
    }

    @Test
    void leavesAPeriodThatHasNotEndedAlone() {
        subscription(daysFromNow(5), false);
        when(addons.findAll()).thenReturn(List.of());

        assertFalse(service.runPeriodRollover());
        verify(subscriptions, never()).save(any());
    }

    @Test
    void renewsAnEndedPeriodThatWasNotCancelled() {
        CompanySubscription sub = subscription(daysFromNow(-1), false);
        when(addons.findAll()).thenReturn(List.of());

        assertTrue(service.runPeriodRollover());
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertTrue(sub.getCurrentPeriodEnd().isAfter(Instant.now()), "period extended into the future");
    }

    @Test
    void lapsesAnEndedPeriodThatWasCancelled() {
        CompanySubscription sub = subscription(daysFromNow(-1), true);
        when(addons.findAll()).thenReturn(List.of());

        assertTrue(service.runPeriodRollover());
        assertEquals(SubscriptionStatus.EXPIRED, sub.getStatus());
    }

    @Test
    void renewsAnEndedAddonAndDeletesACancelledOne() {
        when(subscriptions.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        CompanyAddon renewing = addon(daysFromNow(-1), false);
        assertTrue(service.runPeriodRollover());
        assertTrue(renewing.getCurrentPeriodEnd().isAfter(Instant.now()));

        CompanyAddon cancelled = addon(daysFromNow(-1), true);
        assertTrue(service.runPeriodRollover());
        verify(addons).delete(cancelled);
    }
}
