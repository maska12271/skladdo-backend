package com.example.skladdo.service;

import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.Invoice;
import com.example.skladdo.model.InvoiceItem;
import com.example.skladdo.model.InvoicePaymentStatus;
import com.example.skladdo.model.SalesOrder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Renders an {@link Invoice} to a PDF (Thymeleaf HTML template -> openhtmltopdf). The PDF is generated
 * on demand from the invoice's snapshotted data each time it is downloaded - nothing is stored on disk -
 * and is streamed only through the authenticated download endpoint.
 */
@Service
public class InvoicePdfService {

    private static final Logger log = LoggerFactory.getLogger(InvoicePdfService.class);

    /** No relative-URL resources appear in the invoice templates (the logo is always an absolute URL). */
    private static final String BASE_URI = "file:///";

    private static final Duration LOGO_PRESIGN_TTL = Duration.ofMinutes(5);

    private final TemplateEngine templateEngine;
    private final StorageService storageService;

    public InvoicePdfService(TemplateEngine templateEngine, StorageService storageService) {
        this.templateEngine = templateEngine;
        this.storageService = storageService;
    }

    /**
     * Renders the invoice to PDF bytes using the layout selected in {@code settings}. The buyer side
     * comes from the invoice's own snapshot; the seller side from {@code company} + {@code settings}.
     * The chosen {@link com.example.skladdo.model.InvoiceTemplate} maps to a template under
     * {@code templates/invoice/}; the appearance toggles and accent colour are passed as context so the
     * template can honour them.
     */
    public byte[] render(Invoice invoice, Company company, CompanySettings settings) {
        String html = buildHtml(invoice, company, settings);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, BASE_URI);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render invoice PDF for " + invoice.getInvoiceNumber(), e);
        }
    }

    /** Processes the selected Thymeleaf layout to the (X)HTML that is then fed to openhtmltopdf. */
    String buildHtml(Invoice invoice, Company company, CompanySettings settings) {
        Context context = new Context();
        context.setVariable("invoice", invoice);
        context.setVariable("company", company);
        context.setVariable("settings", settings);
        // Only resolve a logo URI when the company both has a logo and wants it shown.
        String logoUri = settings.getInvoiceShowLogo() ? resolveLogoUri(settings.getLogoKey()) : null;
        context.setVariable("logoUri", logoUri);
        // The accent is inlined unescaped into the templates' CSS, so constrain it to a hex colour here -
        // anything else falls back to the default rather than reaching the stylesheet.
        context.setVariable("accent", safeAccent(settings.getInvoiceAccentColor()));

        String footer = settings.getInvoiceFooterText();
        context.setVariable("footerText", footer != null && !footer.isBlank() ? footer : null);

        return templateEngine.process(templateName(settings), context);
    }

    /**
     * Renders a representative sample invoice with the given {@code settings}, used by the settings page
     * to preview a layout choice before it is saved. Nothing is persisted - the invoice is built in memory
     * with placeholder buyer and line data so the seller details, appearance toggles and accent colour from
     * {@code settings} are what actually drive the look.
     */
    public byte[] renderSample(Company company, CompanySettings settings) {
        return render(buildSampleInvoice(settings), company, settings);
    }

    /** The sample invoice rendered to (X)HTML rather than PDF; used by tests to assert on the markup. */
    String buildSampleHtml(Company company, CompanySettings settings) {
        return buildHtml(buildSampleInvoice(settings), company, settings);
    }

    /** Builds an in-memory, non-persisted invoice with plausible placeholder data for the preview. */
    private Invoice buildSampleInvoice(CompanySettings settings) {
        LocalDate issueDate = LocalDate.now();
        int termDays = settings.getInvoicePaymentTermDays() != null ? settings.getInvoicePaymentTermDays() : 14;

        Invoice invoice = new Invoice();
        invoice.setStatus(InvoicePaymentStatus.UNPAID);
        invoice.setInvoiceNumber(settings.getInvoiceNumberPrefix() + "000123");
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(issueDate.plusDays(termDays));
        invoice.setCurrency(settings.getCurrency());
        invoice.setPenaltyPercent(settings.getLatePaymentPenaltyPercent());
        invoice.setPenaltyPeriod(settings.getPenaltyPeriod());
        invoice.setNotes("Sample invoice - this is how your invoices will look with the selected layout.");

        // A sales order is only read for its number in the templates; a transient one is enough.
        SalesOrder sampleOrder = new SalesOrder();
        sampleOrder.setOrderNumber("SO-000045");
        invoice.setSalesOrder(sampleOrder);

        invoice.setClientName("Northwind Trading OÜ");
        invoice.setClientAddress("12 Harbour Road, 10115 Tallinn, Estonia");
        invoice.setClientRegistrationCode("EE10293847");
        invoice.setClientEmail("billing@northwind.example");

        addSampleLine(invoice, "Oak office desk", "DSK-OAK-160", 2, "249.00", "0", "22");
        addSampleLine(invoice, "Ergonomic chair", "CHR-ERG-02", 4, "159.50", "10", "22");
        addSampleLine(invoice, "Delivery & assembly", "SRV-DLV", 1, "80.00", "0", "0");

        BigDecimal subtotal = invoice.getItems().stream()
                .map(InvoiceItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = subtotal.multiply(new BigDecimal("0.22")).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal delivery = new BigDecimal("15.00");
        invoice.setSubtotalAmount(subtotal);
        invoice.setTaxAmount(tax);
        invoice.setDeliveryPrice(delivery);
        invoice.setTotalAmount(subtotal.add(tax).add(delivery));
        return invoice;
    }

    private void addSampleLine(Invoice invoice, String name, String sku, int qty,
                               String unitPrice, String discountPercent, String taxPercent) {
        InvoiceItem line = new InvoiceItem();
        line.setInvoice(invoice);
        line.setProductName(name);
        line.setSku(sku);
        line.setQuantity(qty);
        BigDecimal unit = new BigDecimal(unitPrice);
        BigDecimal discount = new BigDecimal(discountPercent);
        line.setUnitPrice(unit);
        line.setDiscountPercent(discount);
        line.setTaxRatePercent(new BigDecimal(taxPercent));
        // Net line amount after discount, before tax (mirrors how real lines are snapshotted).
        BigDecimal gross = unit.multiply(BigDecimal.valueOf(qty));
        BigDecimal net = gross.subtract(gross.multiply(discount).movePointLeft(2)).setScale(2, java.math.RoundingMode.HALF_UP);
        line.setLineTotal(net);
        invoice.getItems().add(line);
    }

    /** Default accent, kept in sync with {@link CompanySettings#getInvoiceAccentColor()}. */
    private static final String DEFAULT_ACCENT = "#0f766e";

    /** A 3-, 6- or 8-digit hex colour, with the leading {@code #}. */
    private static final java.util.regex.Pattern HEX_COLOR =
            java.util.regex.Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    /** Returns {@code accent} if it is a valid hex colour, otherwise the default. */
    private String safeAccent(String accent) {
        return accent != null && HEX_COLOR.matcher(accent).matches() ? accent : DEFAULT_ACCENT;
    }

    /** Maps the selected layout to its Thymeleaf template path under {@code templates/invoice/}. */
    private String templateName(CompanySettings settings) {
        return switch (settings.getInvoiceTemplate()) {
            case MODERN -> "invoice/modern";
            case MINIMAL -> "invoice/minimal";
            case CLASSIC -> "invoice/classic";
        };
    }

    /**
     * Maps a stored logo S3 key to a short-lived presigned URL openhtmltopdf can load. Returns
     * {@code null} when there is no logo or it cannot be resolved, in which case the template falls
     * back to the company name.
     */
    private String resolveLogoUri(String logoKey) {
        if (logoKey == null || logoKey.isBlank()) {
            return null;
        }
        try {
            return storageService.presign(logoKey, LOGO_PRESIGN_TTL);
        } catch (Exception e) {
            log.warn("Could not resolve invoice logo '{}': {}", logoKey, e.getMessage());
            return null;
        }
    }
}
