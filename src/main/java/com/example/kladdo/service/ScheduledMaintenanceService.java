package com.example.kladdo.service;

import com.example.kladdo.model.Company;
import com.example.kladdo.model.Invoice;
import com.example.kladdo.model.InvoicePaymentStatus;
import com.example.kladdo.model.NotificationType;
import com.example.kladdo.model.PermissionModule;
import com.example.kladdo.model.Product;
import com.example.kladdo.model.Tender;
import com.example.kladdo.repository.CompanyRepository;
import com.example.kladdo.repository.InvoiceRepository;
import com.example.kladdo.repository.ProductRepository;
import com.example.kladdo.repository.TenderRepository;
import com.example.kladdo.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Background maintenance that has to happen whether or not anyone is using the app. Enabled by
 * {@code com.example.kladdo.config.SchedulingConfig}.
 *
 * <p>Every job here is defensive by design: a scheduled thread has no request around it, so a thrown
 * exception would only surface in the log and could silently kill the remaining work. Per-company work is
 * therefore wrapped individually - one broken tenant must never abort the sweep for the others.</p>
 *
 * <p>Jobs run on the <em>server's</em> clock. Per-company local-time scheduling (using
 * {@code CompanySettings.timezone}) becomes relevant once user-facing reminders are added.</p>
 */
@Service
public class ScheduledMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMaintenanceService.class);

    /** How far ahead a tender deadline is worth warning about. */
    private static final int DEADLINE_HORIZON_DAYS = 3;

    private final CompanyRepository companyRepository;
    private final PlanService planService;
    private final EcbRateService ecbRateService;
    private final NotificationService notificationService;
    private final InvoiceRepository invoiceRepository;
    private final TenderRepository tenderRepository;
    private final ProductRepository productRepository;

    public ScheduledMaintenanceService(CompanyRepository companyRepository,
                                       PlanService planService,
                                       EcbRateService ecbRateService,
                                       NotificationService notificationService,
                                       InvoiceRepository invoiceRepository,
                                       TenderRepository tenderRepository,
                                       ProductRepository productRepository) {
        this.companyRepository = companyRepository;
        this.planService = planService;
        this.ecbRateService = ecbRateService;
        this.notificationService = notificationService;
        this.invoiceRepository = invoiceRepository;
        this.tenderRepository = tenderRepository;
        this.productRepository = productRepository;
    }

    /**
     * Advances subscription and add-on billing periods that have run out, for every company. The same
     * rollover is applied lazily whenever the billing page is read; this nightly pass means a company
     * nobody opens still rolls over on time (and, once billing is wired to a provider, is charged on time).
     *
     * <p>Runs at 03:15 server time - late enough to be quiet, and deliberately not on the hour.</p>
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void rollOverBillingPeriods() {
        int rolled = sweepCompanies("billing rollover", () -> planService.runPeriodRollover() ? 1 : 0);
        if (rolled > 0) {
            log.info("Billing rollover advanced {} companies", rolled);
        }
    }

    /**
     * Runs {@code perCompany} once for every company with that company's tenant bound, summing what it
     * reports and never letting one tenant's failure abort the rest.
     *
     * <p>The tenant must be bound <em>outside</em> the transactional call, not inside it: Hibernate reads
     * the tenant id when it opens the session.</p>
     */
    private int sweepCompanies(String jobName, Supplier<Integer> perCompany) {
        int total = 0;
        int failed = 0;
        for (Company company : companyRepository.findAll()) {
            try {
                total += TenantContext.callAs(company.getId(), perCompany);
            } catch (Exception e) {
                failed++;
                log.warn("{} failed for company {}: {}", jobName, company.getId(), e.toString());
            }
        }
        if (failed > 0) {
            log.warn("{} finished with {} failed companies", jobName, failed);
        }
        return total;
    }

    // --- Notification producers -------------------------------------------------------------------
    // Each alerts the users who can view the relevant module; NotificationService applies the per-user
    // mutes and the dedupe key, so a condition that persists for weeks still only notifies once.

    /** Tells invoice viewers about invoices that are past due and still unpaid. Runs at 07:00. */
    @Scheduled(cron = "${app.jobs.overdue-invoices-cron:0 0 7 * * *}")
    public void notifyOverdueInvoices() {
        int sent = sweepCompanies("overdue-invoice notifications", () -> {
            int created = 0;
            for (Invoice invoice : invoiceRepository.findByStatusAndDueDateBefore(
                    InvoicePaymentStatus.UNPAID, LocalDate.now())) {
                created += notificationService.notifyModuleViewers(
                        PermissionModule.INVOICES,
                        NotificationType.INVOICE_OVERDUE,
                        invoice.getInvoiceNumber(),
                        invoice.getSalesOrder() != null ? "/sales-orders/" + invoice.getSalesOrder().getId() : null,
                        "INVOICE_OVERDUE:" + invoice.getId());
            }
            return created;
        });
        logSent("overdue invoices", sent);
    }

    /** Warns tender viewers about deadlines landing within the next few days. Runs at 07:05. */
    @Scheduled(cron = "${app.jobs.tender-deadlines-cron:0 5 7 * * *}")
    public void notifyTenderDeadlines() {
        int sent = sweepCompanies("tender-deadline notifications", () -> {
            LocalDate today = LocalDate.now();
            int created = 0;
            for (Tender tender : tenderRepository.findUpcomingDeadlines(today, today.plusDays(DEADLINE_HORIZON_DAYS))) {
                created += notificationService.notifyModuleViewers(
                        PermissionModule.TENDERS,
                        NotificationType.TENDER_DEADLINE,
                        tender.getTitle(),
                        "/tenders/" + tender.getId(),
                        "TENDER_DEADLINE:" + tender.getId());
            }
            return created;
        });
        logSent("tender deadlines", sent);
    }

    /** Tells product viewers which products dropped below their minimum stock. Runs at 07:10. */
    @Scheduled(cron = "${app.jobs.low-stock-cron:0 10 7 * * *}")
    public void notifyLowStock() {
        int sent = sweepCompanies("low-stock notifications", () -> {
            int created = 0;
            for (Product product : productRepository.findLowStock()) {
                created += notificationService.notifyModuleViewers(
                        PermissionModule.PRODUCTS,
                        NotificationType.LOW_STOCK,
                        product.getName(),
                        "/products/" + product.getId(),
                        "LOW_STOCK:" + product.getId());
            }
            return created;
        });
        logSent("low stock", sent);
    }

    private static void logSent(String what, int sent) {
        if (sent > 0) {
            log.info("Sent {} {} notifications", sent, what);
        }
    }

    /**
     * Keeps the ECB exchange-rate snapshot warm so the first order form of the day does not pay the feed's
     * latency inline. The service caches for 6h and swallows its own failures, so this is a no-op refresh
     * whenever the cache is still fresh.
     *
     * <p>The initial delay keeps startup (and test boots) free of an outbound call.</p>
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT6H")
    public void warmExchangeRates() {
        try {
            ecbRateService.warmCache();
        } catch (Exception e) {
            log.warn("Exchange-rate warm-up failed: {}", e.toString());
        }
    }
}
