package com.example.skladdo.service;

import com.example.skladdo.dto.CompanySettingsDto;
import com.example.skladdo.dto.DisplaySettingsDto;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.repository.CompanySettingsRepository;
import com.example.skladdo.repository.TaxRateRepository;
import com.example.skladdo.security.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Reads and updates the calling company's {@link CompanySettings}. The row is created lazily with
 * sensible defaults on first access, so companies that predate this feature work without a migration.
 */
@Service
public class CompanySettingsService {

    private final CompanySettingsRepository repository;
    private final TaxRateRepository taxRateRepository;
    private final EncryptionService encryptionService;

    public CompanySettingsService(CompanySettingsRepository repository,
                                  TaxRateRepository taxRateRepository,
                                  EncryptionService encryptionService) {
        this.repository = repository;
        this.taxRateRepository = taxRateRepository;
        this.encryptionService = encryptionService;
    }

    /** The company's settings, creating the default row if it does not exist yet. */
    @Transactional
    public CompanySettings getOrCreate() {
        return repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(new CompanySettings()));
    }

    @Transactional(readOnly = true)
    public CompanySettingsDto get() {
        return repository.findFirstByOrderByIdAsc()
                .map(CompanySettingsDto::from)
                // Don't persist on a read; just return the in-memory defaults.
                .orElseGet(() -> CompanySettingsDto.from(new CompanySettings()));
    }

    @Transactional
    public CompanySettingsDto update(CompanySettingsDto dto) {
        CompanySettings settings = getOrCreate();
        dto.applyTo(settings);
        // Re-encrypt the SMTP password only when a new one was actually supplied; a blank/absent value
        // means "keep the stored one" (the DTO never round-trips the existing password back to us).
        if (dto.smtpPassword() != null && !dto.smtpPassword().isBlank()) {
            settings.setSmtpPasswordEncrypted(encryptionService.encrypt(dto.smtpPassword()));
        }
        // Deliberately not audited: a settings save has no natural identifier and no per-field detail, so
        // the trail only ever showed a contentless "Settings updated" line. The company's own identity
        // (CompanyService) is still audited, because a rename is a meaningful, identifiable change.
        return CompanySettingsDto.from(repository.save(settings));
    }

    /**
     * Reserves the next invoice number for the calling company and advances the counter. Must run inside
     * the invoice-generation transaction so that, if generation later fails and rolls back, the reserved
     * number is released rather than skipped. Format: {@code prefix + 6-digit zero-padded sequence},
     * e.g. {@code INV-000123}.
     */
    @Transactional
    public String allocateNextInvoiceNumber() {
        CompanySettings settings = getOrCreate();
        int next = (settings.getLastInvoiceNumber() != null ? settings.getLastInvoiceNumber() : 0) + 1;
        settings.setLastInvoiceNumber(next);
        repository.save(settings);
        return settings.getInvoiceNumberPrefix() + String.format("%06d", next);
    }

    /** Fixed prefix for system-suggested sales-order numbers (not configurable, unlike invoice numbers). */
    private static final String SALES_ORDER_PREFIX = "SO-";

    /**
     * The next sales-order number the create form should suggest, <em>without</em> advancing the counter.
     * Several users opening the form see the same suggestion; the counter only moves when an order is
     * actually saved with it (see {@link #allocateNextSalesOrderNumber()}).
     */
    @Transactional(readOnly = true)
    public String peekNextSalesOrderNumber() {
        Integer last = repository.findFirstByOrderByIdAsc()
                .map(CompanySettings::getLastSalesOrderNumber)
                .orElse(null);
        return formatSalesOrderNumber((last != null ? last : 0) + 1);
    }

    /**
     * Reserves the next sales-order number for the calling company and advances the counter. Runs in the
     * order-creation transaction so a rollback releases the reserved number.
     */
    @Transactional
    public String allocateNextSalesOrderNumber() {
        CompanySettings settings = getOrCreate();
        int next = (settings.getLastSalesOrderNumber() != null ? settings.getLastSalesOrderNumber() : 0) + 1;
        settings.setLastSalesOrderNumber(next);
        repository.save(settings);
        return formatSalesOrderNumber(next);
    }

    private static String formatSalesOrderNumber(int sequence) {
        return SALES_ORDER_PREFIX + String.format("%06d", sequence);
    }

    /** Fixed prefix for system-suggested purchase-order numbers. */
    private static final String PURCHASE_ORDER_PREFIX = "PO-";

    /** The next purchase-order number the create form should suggest, without advancing the counter. */
    @Transactional(readOnly = true)
    public String peekNextPurchaseOrderNumber() {
        Integer last = repository.findFirstByOrderByIdAsc()
                .map(CompanySettings::getLastPurchaseOrderNumber)
                .orElse(null);
        return formatPurchaseOrderNumber((last != null ? last : 0) + 1);
    }

    /** Reserves the next purchase-order number and advances the counter (runs in the create transaction). */
    @Transactional
    public String allocateNextPurchaseOrderNumber() {
        CompanySettings settings = getOrCreate();
        int next = (settings.getLastPurchaseOrderNumber() != null ? settings.getLastPurchaseOrderNumber() : 0) + 1;
        settings.setLastPurchaseOrderNumber(next);
        repository.save(settings);
        return formatPurchaseOrderNumber(next);
    }

    private static String formatPurchaseOrderNumber(int sequence) {
        return PURCHASE_ORDER_PREFIX + String.format("%06d", sequence);
    }

    /**
     * The next tender number the create form should suggest, without advancing the counter. Unlike orders
     * the prefix is configurable ({@link CompanySettings#getTenderNumberPrefix()}).
     */
    @Transactional(readOnly = true)
    public String peekNextTenderNumber() {
        CompanySettings settings = repository.findFirstByOrderByIdAsc().orElseGet(CompanySettings::new);
        Integer last = settings.getLastTenderNumber();
        return formatTenderNumber(settings, (last != null ? last : 0) + 1);
    }

    /** Reserves the next tender number and advances the counter (runs in the create transaction). */
    @Transactional
    public String allocateNextTenderNumber() {
        CompanySettings settings = getOrCreate();
        int next = (settings.getLastTenderNumber() != null ? settings.getLastTenderNumber() : 0) + 1;
        settings.setLastTenderNumber(next);
        repository.save(settings);
        return formatTenderNumber(settings, next);
    }

    private static String formatTenderNumber(CompanySettings settings, int sequence) {
        return settings.getTenderNumberPrefix() + String.format("%06d", sequence);
    }

    /** Display-only settings readable by any authenticated user (drives price rendering across the app). */
    @Transactional(readOnly = true)
    public DisplaySettingsDto getDisplaySettings() {
        CompanySettings settings = repository.findFirstByOrderByIdAsc().orElseGet(CompanySettings::new);
        BigDecimal defaultTaxPercent = taxRateRepository.findFirstByIsDefaultTrue()
                .map(rate -> rate.getPercentage())
                .orElse(BigDecimal.ZERO);
        return new DisplaySettingsDto(
                settings.getCurrency(),
                settings.isPricesIncludeTax(),
                defaultTaxPercent,
                settings.getDefaultWarehouseId(),
                settings.getDefaultPrepaymentPercent(),
                settings.getInvoicePaymentTermDays(),
                settings.getLatePaymentPenaltyPercent(),
                settings.getPenaltyPeriod() != null ? settings.getPenaltyPeriod().name() : null,
                settings.getFirstDayOfWeek(),
                settings.getDateFormat(),
                settings.getTimeFormat(),
                settings.getDefaultProductUnit()
        );
    }
}
