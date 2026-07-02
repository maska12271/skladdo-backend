package com.example.kladdo.service;

import com.example.kladdo.model.Company;
import com.example.kladdo.model.CompanySettings;
import com.example.kladdo.model.InvoiceTemplate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders each invoice layout end-to-end through Thymeleaf + openhtmltopdf with a sample invoice, with no
 * Spring context (so it does not touch the file-based H2 database). Verifies every layout produces a valid
 * PDF, the appearance toggles add/remove content, and - as a side effect - writes a rasterised PNG of each
 * layout to {@code target/invoice-previews/} for visual inspection.
 */
class InvoicePdfServiceRenderTest {

    private static Path uploadDir;
    private static Path previewDir;
    private static InvoicePdfService service;

    @BeforeAll
    static void setUp() throws Exception {
        // Mirror the app's Thymeleaf setup (Spring dialect + SpEL), but with a bare application context so
        // the test never wires up JPA or touches the file-based H2 database.
        GenericApplicationContext appContext = new GenericApplicationContext();
        appContext.refresh();
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(appContext);
        resolver.setPrefix("classpath:/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        uploadDir = Files.createTempDirectory("invoice-uploads");
        writeSampleLogo(uploadDir.resolve("logo.png"));

        previewDir = Path.of("target", "invoice-previews");
        Files.createDirectories(previewDir);

        service = new InvoicePdfService(engine, uploadDir.toString());
    }

    @Test
    void everyLayoutRendersAValidPdfAndIsRasterisedForInspection() throws Exception {
        Company company = sampleCompany();
        for (InvoiceTemplate template : InvoiceTemplate.values()) {
            CompanySettings settings = sampleSettings();
            settings.setInvoiceTemplate(template);
            settings.setLogoUrl("/uploads/logo.png");

            byte[] pdf = service.renderSample(company, settings);

            assertThat(pdf).isNotEmpty();
            assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");

            String text = extractText(pdf);
            assertThat(text).contains("Northwind Trading", "Oak office desk", "INV-000123");

            // The accent must be inlined into the CSS as a raw hex colour, not Thymeleaf-escaped to "\#..."
            // (the escaped form is invalid CSS and silently drops the accent).
            String html = service.buildSampleHtml(company, settings);
            assertThat(html).contains("#b91c1c");
            assertThat(html).doesNotContain("\\#b91c1c");

            rasteriseFirstPage(pdf, previewDir.resolve("invoice-" + template.name().toLowerCase() + ".png"));
            Files.writeString(previewDir.resolve("invoice-" + template.name().toLowerCase() + ".html"), html);
        }
    }

    @Test
    void togglesAddAndRemoveContent() throws Exception {
        Company company = sampleCompany();

        // Everything on: SKU, bank details and notes are all present.
        CompanySettings full = sampleSettings();
        String fullText = extractText(service.renderSample(company, full));
        assertThat(fullText).contains("DSK-OAK-160");                 // SKU column
        assertThat(fullText).contains("Sample Bank");                  // bank details
        assertThat(fullText).contains("Sample invoice");               // notes

        // Toggles off: those sections disappear.
        CompanySettings trimmed = sampleSettings();
        trimmed.setInvoiceShowLineSku(false);
        trimmed.setInvoiceShowBankDetails(false);
        trimmed.setInvoiceShowNotes(false);
        trimmed.setInvoiceShowPaymentTerms(false);
        String trimmedText = extractText(service.renderSample(company, trimmed));
        assertThat(trimmedText).doesNotContain("DSK-OAK-160");
        assertThat(trimmedText).doesNotContain("Sample Bank");
        assertThat(trimmedText).doesNotContain("Sample invoice");
    }

    @Test
    void customFooterReplacesTheDefault() throws Exception {
        Company company = sampleCompany();
        CompanySettings settings = sampleSettings();
        settings.setInvoiceFooterText("Questions? billing@kladdo.example");
        String text = extractText(service.renderSample(company, settings));
        assertThat(text).contains("Questions? billing@kladdo.example");
        assertThat(text).doesNotContain("Generated by Kladdo");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static Company sampleCompany() {
        Company company = new Company();
        company.setName("Kladdo Furniture OÜ");
        company.setRegistrationCode("EE12345678");
        return company;
    }

    private static CompanySettings sampleSettings() {
        CompanySettings settings = new CompanySettings();
        settings.setCurrency("EUR");
        settings.setInvoiceNumberPrefix("INV-");
        settings.setInvoicePaymentTermDays(14);
        settings.setLatePaymentPenaltyPercent(new BigDecimal("0.500"));
        settings.setCompanyAddress("5 Kalamaja, 10415 Tallinn, Estonia");
        settings.setVatNumber("EE101010101");
        settings.setCompanyEmail("hello@kladdo.example");
        settings.setCompanyPhone("+372 555 0100");
        settings.setBankName("Sample Bank");
        settings.setBankIban("EE00 1234 5678 9012 3456");
        // A distinctive (non-default) accent so the previews prove the colour is actually injected.
        settings.setInvoiceAccentColor("#b91c1c");
        return settings;
    }

    private static String extractText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static void rasteriseFirstPage(byte[] pdf, Path target) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(0, 110);
            ImageIO.write(image, "png", target.toFile());
        }
    }

    /** A simple coloured logo with text, so we can see how each layout fits a real logo image. */
    private static void writeSampleLogo(Path target) throws Exception {
        BufferedImage img = new BufferedImage(280, 90, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(15, 118, 110));
        g.fillRoundRect(0, 0, 280, 90, 16, 16);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString("KLADDO", 24, 58);
        g.dispose();
        ImageIO.write(img, "png", target.toFile());
    }
}
