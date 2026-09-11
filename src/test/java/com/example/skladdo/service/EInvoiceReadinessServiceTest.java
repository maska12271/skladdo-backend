package com.example.skladdo.service;

import com.example.skladdo.dto.EInvoiceIssueDto;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.Invoice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-export check. Its value is entirely in what it notices, so each test removes exactly one thing
 * from an otherwise complete invoice and asserts that it - and nothing else - is reported.
 */
class EInvoiceReadinessServiceTest {

    private final EInvoiceReadinessService service = new EInvoiceReadinessService(new EInvoiceXmlService());

    @Test
    void aCompleteInvoiceHasNothingToReport() {
        assertThat(check(invoice(), company(), settings())).isEmpty();
    }

    // --- Blocking: a mandatory element would go out empty -----------------------------------------

    @Test
    void missingSellerRegistrationCodeBlocks() {
        Company company = company();
        company.setRegistrationCode(null);
        assertThat(check(invoice(), company, settings()))
                .containsExactly(EInvoiceIssueDto.blocking("sellerRegistrationCode"));
    }

    @Test
    void missingIbanBlocksBecauseTheFileCannotBePaidFrom() {
        CompanySettings settings = settings();
        settings.setBankIban("   ");
        assertThat(check(invoice(), company(), settings))
                .containsExactly(EInvoiceIssueDto.blocking("sellerBankIban"));
    }

    @Test
    void missingBuyerNameBlocks() {
        Invoice invoice = invoice();
        invoice.setClientName(null);
        assertThat(check(invoice, company(), settings()))
                .containsExactly(EInvoiceIssueDto.blocking("buyerName"));
    }

    // --- Warnings: valid, but weaker than receivers expect -----------------------------------------

    @Test
    void missingVatNumbersAreWarnings() {
        CompanySettings settings = settings();
        settings.setVatNumber(null);
        assertThat(check(invoice(), company(), settings))
                .containsExactly(EInvoiceIssueDto.warning("sellerVatNumber"));

        Invoice invoice = invoice();
        invoice.setClientVatNumber(null);
        assertThat(check(invoice, company(), settings()))
                .containsExactly(EInvoiceIssueDto.warning("buyerVatNumber"));
    }

    @Test
    void missingBuyerRegistrationCodeIsAWarning() {
        Invoice invoice = invoice();
        invoice.setClientRegistrationCode(null);
        assertThat(check(invoice, company(), settings()))
                .containsExactly(EInvoiceIssueDto.warning("buyerRegistrationCode"));
    }

    @Test
    void anAddressMissingItsCityIsReportedBecauseTheBlockWouldBeDropped() {
        // Half an address is not emitted at all, so the user is told rather than left to discover it.
        CompanySettings settings = settings();
        settings.setCompanyAddressCity(null);
        assertThat(check(invoice(), company(), settings))
                .containsExactly(EInvoiceIssueDto.warning("sellerAddress"));

        Invoice invoice = invoice();
        invoice.setClientAddressCity(null);
        invoice.setClientAddress(null); // no legacy line to fall back to either
        assertThat(check(invoice, company(), settings()))
                .containsExactly(EInvoiceIssueDto.warning("buyerAddress"));
    }

    @Test
    void aLegacyInvoiceWhoseAddressLineStillParsesIsNotReported() {
        // The emitter falls back to parsing the printed line, so the check must not contradict it.
        Invoice invoice = invoice();
        invoice.setClientAddressStreet(null);
        invoice.setClientAddressCity(null);
        invoice.setClientAddress("Ravi 18, 10138 Tallinn");
        assertThat(check(invoice, company(), settings())).isEmpty();
    }

    @Test
    void everythingMissingIsReportedAtOnceWithBlockingFirst() {
        List<EInvoiceIssueDto> issues = check(new Invoice(), new Company(), new CompanySettings());
        assertThat(issues).extracting(EInvoiceIssueDto::severity)
                .startsWith(EInvoiceIssueDto.Severity.BLOCKING, EInvoiceIssueDto.Severity.BLOCKING,
                        EInvoiceIssueDto.Severity.BLOCKING);
        assertThat(issues).extracting(EInvoiceIssueDto::code)
                .containsExactly("sellerRegistrationCode", "sellerBankIban", "buyerName",
                        "sellerVatNumber", "sellerAddress", "buyerRegistrationCode", "buyerVatNumber",
                        "buyerAddress");
    }

    private List<EInvoiceIssueDto> check(Invoice invoice, Company company, CompanySettings settings) {
        return service.check(invoice, company, settings);
    }

    private static Company company() {
        Company company = new Company();
        company.setName("Näidis OÜ");
        company.setRegistrationCode("12345678");
        return company;
    }

    private static CompanySettings settings() {
        CompanySettings settings = new CompanySettings();
        settings.setVatNumber("EE101234567");
        settings.setBankIban("EE241010002028538005");
        settings.setCompanyAddressStreet("Tartu mnt 5");
        settings.setCompanyAddressCity("Tallinn");
        return settings;
    }

    private static Invoice invoice() {
        Invoice invoice = new Invoice();
        invoice.setClientName("City Hospital");
        invoice.setClientRegistrationCode("10293847");
        invoice.setClientVatNumber("EE102938470");
        invoice.setClientAddressStreet("Ravi 18");
        invoice.setClientAddressCity("Tallinn");
        return invoice;
    }
}
