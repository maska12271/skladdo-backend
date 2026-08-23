package com.example.skladdo.controller;

import com.example.skladdo.dto.CompanySettingsDto;
import com.example.skladdo.dto.DisplaySettingsDto;
import com.example.skladdo.dto.ModulePermissionDto;
import com.example.skladdo.dto.TaxRateDto;
import com.example.skladdo.dto.TestEmailRequest;
import com.example.skladdo.dto.TestEmailResult;
import com.example.skladdo.dto.UpdatePermissionsRequest;
import com.example.skladdo.service.CompanySettingsService;
import com.example.skladdo.service.EmailSendingService;
import com.example.skladdo.service.InvoiceService;
import com.example.skladdo.service.PermissionService;
import com.example.skladdo.service.TaxRateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Company-wide settings: general/invoicing preferences, the tax-rate catalogue, and the default
 * permission template applied to new users. Restricted to owners and administrators; every operation
 * is scoped to the caller's company.
 */
@RestController
@RequestMapping("/api/settings")
@Tag(name = "Settings")
@PreAuthorize("hasAnyRole('OWNER', 'ADMINISTRATOR')")
public class SettingsController {

    private final CompanySettingsService settingsService;
    private final TaxRateService taxRateService;
    private final PermissionService permissionService;
    private final InvoiceService invoiceService;
    private final EmailSendingService emailSendingService;

    public SettingsController(CompanySettingsService settingsService,
                              TaxRateService taxRateService,
                              PermissionService permissionService,
                              InvoiceService invoiceService,
                              EmailSendingService emailSendingService) {
        this.settingsService = settingsService;
        this.taxRateService = taxRateService;
        this.permissionService = permissionService;
        this.invoiceService = invoiceService;
        this.emailSendingService = emailSendingService;
    }

    // --- Display settings (any authenticated user) -----------------------------------------------

    /**
     * The price-display subset of settings, readable by every authenticated user so non-admin pages
     * (e.g. the product catalogue) can render prices with the company's currency and tax preference.
     * Overrides the class-level admin restriction for this one read-only endpoint.
     */
    @GetMapping("/display")
    @PreAuthorize("isAuthenticated()")
    public DisplaySettingsDto getDisplaySettings() {
        return settingsService.getDisplaySettings();
    }

    // --- General / invoicing settings ------------------------------------------------------------

    @GetMapping
    public CompanySettingsDto get() {
        return settingsService.get();
    }

    @PutMapping
    public CompanySettingsDto update(@Valid @RequestBody CompanySettingsDto request) {
        return settingsService.update(request);
    }

    /**
     * Renders a sample invoice PDF for the supplied (unsaved) settings so the settings page can show a
     * live preview of the selected layout, accent colour and toggles before they are saved.
     */
    @PostMapping("/invoice-preview")
    public ResponseEntity<ByteArrayResource> previewInvoice(@Valid @RequestBody CompanySettingsDto request) {
        byte[] pdf = invoiceService.renderPreview(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"invoice-preview.pdf\"")
                .body(new ByteArrayResource(pdf));
    }

    /**
     * Sends a test email to the supplied address using the company's saved SMTP settings, so an admin can
     * confirm the configuration works. Returns the outcome (including the SMTP error on failure) rather
     * than throwing, so the settings page can show a precise success/failure message.
     */
    @PostMapping("/email/test-send")
    public TestEmailResult testEmail(@Valid @RequestBody TestEmailRequest request) {
        return emailSendingService.sendTest(request.recipient());
    }

    // --- Tax rates -------------------------------------------------------------------------------

    /** Readable by any authenticated user so product forms can offer the rate list; writes stay admin-only. */
    @GetMapping("/tax-rates")
    @PreAuthorize("isAuthenticated()")
    public List<TaxRateDto> getTaxRates() {
        return taxRateService.findAll();
    }

    @PostMapping("/tax-rates")
    public TaxRateDto createTaxRate(@Valid @RequestBody TaxRateDto request) {
        return taxRateService.create(request);
    }

    @PutMapping("/tax-rates/{id}")
    public TaxRateDto updateTaxRate(@PathVariable Long id, @Valid @RequestBody TaxRateDto request) {
        return taxRateService.update(id, request);
    }

    @DeleteMapping("/tax-rates/{id}")
    public void deleteTaxRate(@PathVariable Long id) {
        taxRateService.delete(id);
    }

    // --- Default permission template (for new users) ---------------------------------------------

    @GetMapping("/default-permissions")
    public List<ModulePermissionDto> getDefaultPermissions() {
        return permissionService.defaultPermissionTemplate();
    }

    @PutMapping("/default-permissions")
    public List<ModulePermissionDto> updateDefaultPermissions(@Valid @RequestBody UpdatePermissionsRequest request) {
        return permissionService.replaceDefaultPermissionTemplate(request.permissions());
    }
}
