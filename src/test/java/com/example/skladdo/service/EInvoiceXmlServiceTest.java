package com.example.skladdo.service;

import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.Invoice;
import com.example.skladdo.model.InvoiceItem;
import com.example.skladdo.model.InvoicePaymentStatus;
import com.example.skladdo.model.InvoiceType;
import com.example.skladdo.model.SalesOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds a representative invoice - mixed VAT rates, a line discount, a delivery charge and an applied
 * prepayment - and asserts the Estonian e-invoice XML it produces. No Spring context: the service has no
 * collaborators, so the test stays a plain unit test.
 */
class EInvoiceXmlServiceTest {

    private static Document doc;
    private static String raw;

    @BeforeAll
    static void renderOnce() throws Exception {
        byte[] xml = new EInvoiceXmlService().render(invoice(), company(), settings());
        raw = new String(xml, StandardCharsets.UTF_8);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        doc = factory.newDocumentBuilder()
                .parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    // ---------------------------------------------------------------------------------------------
    // The department identifier - the reason this export exists for buyers who require one
    // ---------------------------------------------------------------------------------------------

    @Test
    void buyerDepartmentIdIsEmittedInSchemaOrder() {
        assertThat(xpath("/E_Invoice/Invoice/InvoiceParties/BuyerParty/DepId")).isEqualTo("Procurement dept.");

        // The schema is an xs:sequence, so position is part of the contract: DepId sits between Name and
        // RegNumber. A receiver validating against the XSD rejects the file outright if it drifts.
        assertThat(childNames("/E_Invoice/Invoice/InvoiceParties/BuyerParty"))
                .containsSubsequence("Name", "DepId", "RegNumber", "VATRegNumber");
    }

    @Test
    void buyerDepartmentIdIsOmittedWhenTheClientHasNone() throws Exception {
        Invoice invoice = invoice();
        invoice.setClientDepartmentId(null);

        Document without = parse(new EInvoiceXmlService().render(invoice, company(), settings()));
        assertThat(xpath(without, "/E_Invoice/Invoice/InvoiceParties/BuyerParty/DepId")).isEmpty();
        // The rest of the buyer block is unaffected.
        assertThat(xpath(without, "/E_Invoice/Invoice/InvoiceParties/BuyerParty/Name")).isEqualTo("City Hospital");
    }

    // ---------------------------------------------------------------------------------------------
    // Parties
    // ---------------------------------------------------------------------------------------------

    @Test
    void sellerAndBuyerIdentifiersAreMapped() {
        assertThat(xpath("/E_Invoice/Invoice/@sellerRegnumber")).isEqualTo("12345678");
        assertThat(xpath("/E_Invoice/Invoice/@regNumber")).isEqualTo("10293847");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceParties/SellerParty/VATRegNumber")).isEqualTo("EE101234567");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceParties/BuyerParty/VATRegNumber")).isEqualTo("EE102938470");
    }

    @Test
    void ampersandInTheCompanyNameIsEscaped() {
        assertThat(xpath("/E_Invoice/Invoice/InvoiceParties/SellerParty/Name")).isEqualTo("Näidis & Pojad OÜ");
        // The ampersand is escaped in the raw document; the accented letters ride along as plain UTF-8,
        // which the standard mandates as the file encoding.
        assertThat(raw).contains("<Name>Näidis &amp; Pojad OÜ</Name>");
    }

    @Test
    void ibanIsNormalisedToTheAccountPattern() {
        // AccountType only permits digits and capitals, so the spaces people type in settings must go.
        assertThat(xpath("/E_Invoice/Invoice/PaymentInfo/PayToAccount")).isEqualTo("EE241010002028538005");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceParties/SellerParty/AccountInfo/IBAN"))
                .isEqualTo("EE241010002028538005");
    }

    @Test
    void structuredAddressPartsAreEmittedInSchemaOrder() {
        String mail = "/E_Invoice/Invoice/InvoiceParties/BuyerParty/ContactData/MailAddress";
        assertThat(xpath(mail + "/PostalAddress1")).isEqualTo("Ravi 18");
        assertThat(xpath(mail + "/City")).isEqualTo("Tallinn");
        assertThat(xpath(mail + "/PostalCode")).isEqualTo("10138");
        assertThat(xpath(mail + "/Country")).isEqualTo("Estonia");
        assertThat(childNames(mail)).containsExactly("PostalAddress1", "City", "PostalCode", "Country");

        // The seller's address comes from settings rather than the snapshot, but takes the same shape.
        String sellerMail = "/E_Invoice/Invoice/InvoiceParties/SellerParty/ContactData/MailAddress";
        assertThat(xpath(sellerMail + "/PostalAddress1")).isEqualTo("Tartu mnt 5");
        assertThat(xpath(sellerMail + "/City")).isEqualTo("Tallinn");
        assertThat(xpath(sellerMail + "/PostalCode")).isEqualTo("10117");
    }

    @Test
    void legacyInvoiceFallsBackToParsingItsPrintedAddressLine() throws Exception {
        // An invoice issued before addresses were split has only the single line that was printed.
        Invoice invoice = invoice();
        invoice.setClientAddressStreet(null);
        invoice.setClientAddressCity(null);
        invoice.setClientAddressPostalCode(null);
        invoice.setClientAddress("Ravi 18, 10138 Tallinn");

        Document legacy = parse(new EInvoiceXmlService().render(invoice, company(), settings()));
        String mail = "/E_Invoice/Invoice/InvoiceParties/BuyerParty/ContactData/MailAddress";
        assertThat(xpath(legacy, mail + "/PostalAddress1")).isEqualTo("Ravi 18");
        assertThat(xpath(legacy, mail + "/City")).isEqualTo("10138 Tallinn");
    }

    @Test
    void addressBlockIsDroppedWhenTheCityIsUnknown() throws Exception {
        Invoice invoice = invoice();
        invoice.setClientAddressCity(null);
        invoice.setClientAddress("Somewhere with no comma");

        Document without = parse(new EInvoiceXmlService().render(invoice, company(), settings()));
        // City is mandatory inside the block, so emitting a half-filled address would be invalid.
        assertThat(xpath(without, "/E_Invoice/Invoice/InvoiceParties/BuyerParty/ContactData/MailAddress/City"))
                .isEmpty();
        assertThat(xpath(without, "/E_Invoice/Invoice/InvoiceParties/BuyerParty/ContactData/MailAddress/PostalAddress1"))
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Totals and the VAT breakdown
    // ---------------------------------------------------------------------------------------------

    @Test
    void documentTotalsAreTaken() {
        assertThat(xpath("/E_Invoice/Invoice/InvoiceSumGroup/TotalSum")).isEqualTo("1361.98");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceSumGroup/TotalVATSum")).isEqualTo("240.78");
        // Gross less the prepayment already invoiced.
        assertThat(xpath("/E_Invoice/Invoice/InvoiceSumGroup/TotalToPay")).isEqualTo("1000.00");
        assertThat(xpath("/E_Invoice/Invoice/PaymentInfo/PaymentTotalSum")).isEqualTo("1000.00");
    }

    @Test
    void invoiceSumEqualsTheSumOfTheLines() {
        // Delivery rides along as a line, so this identity has to hold for the document to be coherent.
        BigDecimal lineSum = decimals("/E_Invoice/Invoice/InvoiceItem/InvoiceItemGroup/ItemEntry/VAT/SumBeforeVAT")
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(new BigDecimal(xpath("/E_Invoice/Invoice/InvoiceSumGroup/InvoiceSum")))
                .isEqualByComparingTo(lineSum)
                .isEqualByComparingTo("1121.20");
    }

    @Test
    void thereIsOneVatBlockPerDistinctRateAndTheyReconcileToTheTotal() {
        List<BigDecimal> rates = decimals("/E_Invoice/Invoice/InvoiceSumGroup/VAT/VATRate");
        assertThat(rates).hasSize(3);
        assertThat(rates.stream().map(BigDecimal::stripTrailingZeros).map(BigDecimal::toPlainString))
                .containsExactlyInAnyOrder("22", "9", "0");

        // The per-rate figures are recomputed from the lines but must still add up to the tax the buyer
        // was actually billed - the cent of rounding drift is absorbed into the largest taxed group.
        BigDecimal summed = decimals("/E_Invoice/Invoice/InvoiceSumGroup/VAT/VATSum")
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summed).isEqualByComparingTo("240.78");
        assertThat(vatSumForRate("22.00")).isEqualByComparingTo("238.98");
        assertThat(vatSumForRate("9.00")).isEqualByComparingTo("1.80");
        assertThat(vatSumForRate("0.00")).isEqualByComparingTo("0.00");
    }

    @Test
    void totalsSumConsistently() {
        BigDecimal net = new BigDecimal(xpath("/E_Invoice/Invoice/InvoiceSumGroup/InvoiceSum"));
        BigDecimal vat = new BigDecimal(xpath("/E_Invoice/Invoice/InvoiceSumGroup/TotalVATSum"));
        BigDecimal total = new BigDecimal(xpath("/E_Invoice/Invoice/InvoiceSumGroup/TotalSum"));
        assertThat(net.add(vat)).isEqualByComparingTo(total);
    }

    // ---------------------------------------------------------------------------------------------
    // Lines
    // ---------------------------------------------------------------------------------------------

    @Test
    void discountTravelsAsAnAdditionAndItemSumIsBeforeIt() {
        String line = "/E_Invoice/Invoice/InvoiceItem/InvoiceItemGroup/ItemEntry[RowNo='1']";
        assertThat(xpath(line + "/Description")).isEqualTo("Oak office desk");
        assertThat(xpath(line + "/SellerProductId")).isEqualTo("DSK-OAK-160");
        assertThat(xpath(line + "/ItemDetailInfo/ItemAmount")).isEqualTo("2.00");
        assertThat(xpath(line + "/ItemDetailInfo/ItemPrice")).isEqualTo("249.00");
        // ItemSum is the line before discount; the discount is stated separately, per the standard.
        assertThat(xpath(line + "/ItemSum")).isEqualTo("498.00");
        assertThat(xpath(line + "/Addition/@addCode")).isEqualTo("DSC");
        assertThat(xpath(line + "/Addition/AddSum")).isEqualTo("-49.80");
        assertThat(xpath(line + "/Addition/AddRate")).isEqualTo("-10.00");
        // ...and the VAT block works off the discounted net.
        assertThat(xpath(line + "/VAT/SumBeforeVAT")).isEqualTo("448.20");
        assertThat(xpath(line + "/ItemTotal")).isEqualTo("546.80");
    }

    @Test
    void undiscountedLineHasNoAddition() {
        String line = "/E_Invoice/Invoice/InvoiceItem/InvoiceItemGroup/ItemEntry[RowNo='2']";
        assertThat(xpath(line + "/ItemSum")).isEqualTo("638.00");
        assertThat(xpath(line + "/Addition/AddSum")).isEmpty();
    }

    @Test
    void deliveryBecomesAnUntaxedLine() {
        String line = "/E_Invoice/Invoice/InvoiceItem/InvoiceItemGroup/ItemEntry[Description='Delivery']";
        assertThat(xpath(line + "/ItemSum")).isEqualTo("15.00");
        assertThat(xpath(line + "/VAT/VATRate")).isEqualTo("0.00");
        assertThat(xpath(line + "/VAT/VATSum")).isEqualTo("0.00");
    }

    // ---------------------------------------------------------------------------------------------
    // Document envelope
    // ---------------------------------------------------------------------------------------------

    @Test
    void headerFooterAndInvoiceMetadataAreWritten() {
        assertThat(xpath("/E_Invoice/Header/Version")).isEqualTo("1.2");
        assertThat(xpath("/E_Invoice/Header/FileId")).isEqualTo("INV-000123");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceInformation/Type/@type")).isEqualTo("DEB");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceInformation/DocumentName")).isEqualTo("Arve");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceInformation/InvoiceNumber")).isEqualTo("INV-000123");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceInformation/InvoiceDate")).isEqualTo("2026-03-01");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceInformation/DueDate")).isEqualTo("2026-03-15");
        assertThat(xpath("/E_Invoice/Invoice/InvoiceInformation/ContractNumber")).isEqualTo("SO-000045");
        assertThat(xpath("/E_Invoice/Invoice/PaymentInfo/Payable")).isEqualTo("YES");
        assertThat(xpath("/E_Invoice/Invoice/PaymentInfo/Currency")).isEqualTo("EUR");
        assertThat(xpath("/E_Invoice/Footer/TotalNumberInvoices")).isEqualTo("1");
        assertThat(xpath("/E_Invoice/Footer/TotalAmount")).isEqualTo("1000.00");
    }

    @Test
    void prepaymentInvoiceIsNamedAsSuch() throws Exception {
        Invoice invoice = invoice();
        invoice.setType(InvoiceType.PREPAYMENT);

        Document prepayment = parse(new EInvoiceXmlService().render(invoice, company(), settings()));
        assertThat(xpath(prepayment, "/E_Invoice/Invoice/InvoiceInformation/DocumentName"))
                .isEqualTo("Ettemaksuarve");
    }

    @Test
    void creditNoteIsMarkedCreAndNamesTheInvoiceItReverses() throws Exception {
        Invoice original = invoice();
        original.setInvoiceNumber("INV-000100");

        Invoice credit = invoice();
        credit.setInvoiceNumber("INV-000124");
        credit.setType(InvoiceType.CREDIT);
        credit.setCreditedInvoice(original);
        credit.setStatus(InvoicePaymentStatus.PAID);

        Document doc = parse(new EInvoiceXmlService().render(credit, company(), settings()));
        assertThat(xpath(doc, "/E_Invoice/Invoice/InvoiceInformation/Type/@type")).isEqualTo("CRE");
        assertThat(xpath(doc, "/E_Invoice/Invoice/InvoiceInformation/Type/SourceInvoice")).isEqualTo("INV-000100");
        assertThat(xpath(doc, "/E_Invoice/Invoice/InvoiceInformation/DocumentName")).isEqualTo("Kreeditarve");
    }

    @Test
    void creditNoteAsksForNothingBecauseEstonianLawForbidsANegativeTotal() throws Exception {
        Invoice credit = invoice();
        credit.setType(InvoiceType.CREDIT);
        credit.setStatus(InvoicePaymentStatus.PAID);

        Document doc = parse(new EInvoiceXmlService().render(credit, company(), settings()));
        // The amounts stay positive and the reversal is carried by the document type; the standard is
        // explicit that a credit invoice must state 0.00 as the amount due.
        assertThat(xpath(doc, "/E_Invoice/Invoice/InvoiceSumGroup/TotalSum")).isEqualTo("1361.98");
        assertThat(xpath(doc, "/E_Invoice/Invoice/InvoiceSumGroup/TotalToPay")).isEqualTo("0.00");
        assertThat(xpath(doc, "/E_Invoice/Invoice/PaymentInfo/PaymentTotalSum")).isEqualTo("0.00");
        assertThat(xpath(doc, "/E_Invoice/Invoice/PaymentInfo/Payable")).isEqualTo("NO");
        assertThat(xpath(doc, "/E_Invoice/Footer/TotalAmount")).isEqualTo("0.00");
    }

    @Test
    void ordinaryInvoiceCarriesNoSourceInvoice() {
        assertThat(xpath("/E_Invoice/Invoice/InvoiceInformation/Type/SourceInvoice")).isEmpty();
    }

    @Test
    void paidInvoiceIsNotPayable() throws Exception {
        Invoice invoice = invoice();
        invoice.setStatus(InvoicePaymentStatus.PAID);

        Document paid = parse(new EInvoiceXmlService().render(invoice, company(), settings()));
        assertThat(xpath(paid, "/E_Invoice/Invoice/PaymentInfo/Payable")).isEqualTo("NO");
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private static Company company() {
        Company company = new Company();
        company.setName("Näidis & Pojad OÜ");
        company.setRegistrationCode("12345678");
        return company;
    }

    private static CompanySettings settings() {
        CompanySettings settings = new CompanySettings();
        settings.setVatNumber("EE101234567");
        settings.setBankName("SEB");
        settings.setBankIban("EE24 1010 0020 2853 8005");
        settings.setCompanyAddressStreet("Tartu mnt 5");
        settings.setCompanyAddressCity("Tallinn");
        settings.setCompanyAddressPostalCode("10117");
        settings.setCompanyCountry("Estonia");
        settings.setCompanyEmail("arved@naidis.ee");
        settings.setCompanyPhone("+372 555 1234");
        return settings;
    }

    /**
     * Three taxed lines at two rates plus a delivery charge, with a prepayment already invoiced. The
     * snapshot tax is deliberately two cents off the sum of the recomputed per-rate figures, which is what
     * per-line rounding on the order produces in practice and what the reconciliation step has to absorb.
     */
    private static Invoice invoice() {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-000123");
        invoice.setStatus(InvoicePaymentStatus.UNPAID);
        invoice.setType(InvoiceType.FINAL);
        invoice.setIssueDate(LocalDate.of(2026, 3, 1));
        invoice.setDueDate(LocalDate.of(2026, 3, 15));
        invoice.setCurrency("EUR");

        SalesOrder order = new SalesOrder();
        order.setOrderNumber("SO-000045");
        invoice.setSalesOrder(order);

        invoice.setClientName("City Hospital");
        invoice.setClientRegistrationCode("10293847");
        invoice.setClientVatNumber("EE102938470");
        invoice.setClientDepartmentId("Procurement dept.");
        invoice.setClientEmail("procurement@hospital.example");
        invoice.setClientAddress("Ravi 18, 10138 Tallinn");
        invoice.setClientAddressStreet("Ravi 18");
        invoice.setClientAddressCity("Tallinn");
        invoice.setClientAddressPostalCode("10138");
        invoice.setClientCountry("Estonia");

        line(invoice, "Oak office desk", "DSK-OAK-160", 2, "249.00", "10", "22", "448.20");
        line(invoice, "Ergonomic chair", "CHR-ERG-02", 4, "159.50", "0", "22", "638.00");
        line(invoice, "Safety handbook", "BK-SAFE", 1, "20.00", "0", "9", "20.00");

        invoice.setSubtotalAmount(new BigDecimal("1106.20"));
        invoice.setTaxAmount(new BigDecimal("240.78"));
        invoice.setDeliveryPrice(new BigDecimal("15.00"));
        invoice.setTotalAmount(new BigDecimal("1361.98"));
        invoice.setAppliedPrepaymentAmount(new BigDecimal("361.98"));
        return invoice;
    }

    private static void line(Invoice invoice, String name, String sku, int qty, String unitPrice,
                             String discountPercent, String taxPercent, String lineTotal) {
        InvoiceItem item = new InvoiceItem();
        item.setInvoice(invoice);
        item.setProductName(name);
        item.setSku(sku);
        item.setQuantity(qty);
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setDiscountPercent(new BigDecimal(discountPercent));
        item.setTaxRatePercent(new BigDecimal(taxPercent));
        item.setLineTotal(new BigDecimal(lineTotal));
        invoice.getItems().add(item);
    }

    // ---------------------------------------------------------------------------------------------
    // XML helpers
    // ---------------------------------------------------------------------------------------------

    private static Document parse(byte[] xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    private static String xpath(String expression) {
        return xpath(doc, expression);
    }

    private static String xpath(Document document, String expression) {
        try {
            XPath path = XPathFactory.newInstance().newXPath();
            return path.evaluate(expression, document);
        } catch (Exception e) {
            throw new IllegalStateException("Bad XPath: " + expression, e);
        }
    }

    private static List<BigDecimal> decimals(String expression) {
        List<BigDecimal> values = new ArrayList<>();
        for (String value : strings(expression)) {
            values.add(new BigDecimal(value));
        }
        return values;
    }

    private static List<String> strings(String expression) {
        try {
            XPath path = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) path.evaluate(expression, doc, XPathConstants.NODESET);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                values.add(nodes.item(i).getTextContent());
            }
            return values;
        } catch (Exception e) {
            throw new IllegalStateException("Bad XPath: " + expression, e);
        }
    }

    /** The VAT sum of the document-level breakdown block carrying the given rate. */
    private static BigDecimal vatSumForRate(String rate) {
        return new BigDecimal(xpath(
                "/E_Invoice/Invoice/InvoiceSumGroup/VAT[VATRate='" + rate + "']/VATSum"));
    }

    /** Child element names of a node, in document order - used to assert schema sequence positions. */
    private static List<String> childNames(String expression) {
        try {
            XPath path = XPathFactory.newInstance().newXPath();
            Node parent = (Node) path.evaluate(expression, doc, XPathConstants.NODE);
            List<String> names = new ArrayList<>();
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    names.add(children.item(i).getNodeName());
                }
            }
            return names;
        } catch (Exception e) {
            throw new IllegalStateException("Bad XPath: " + expression, e);
        }
    }
}
