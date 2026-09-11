package com.example.skladdo.service;

import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.Invoice;
import com.example.skladdo.model.InvoiceItem;
import com.example.skladdo.model.InvoicePaymentStatus;
import com.example.skladdo.model.InvoiceType;
import com.example.skladdo.model.PenaltyPeriod;
import com.example.skladdo.model.PostalAddress;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an {@link Invoice} as an Estonian e-invoice ("e-arve") XML document, version 1.2 - the
 * machine-readable format Estonian accounting systems (Merit Aktiva, Directo, e-arveldaja) import
 * directly, and which becomes mandatory for B2B invoicing in 2027. Like the PDF, the document is built
 * on demand from the invoice's own snapshotted data; nothing is stored.
 *
 * <p>The schema is a strict {@code xs:sequence} throughout, so <em>element order within each block is
 * part of the contract</em> and the emit methods below follow the XSD order exactly - notably
 * {@code DepId} sits between {@code Name} and {@code RegNumber} in every party block. The document is
 * built as a DOM rather than string-concatenated so escaping and well-formedness are guaranteed.</p>
 *
 * <p>This produces the file only. Delivering it over the Peppol network additionally requires an
 * agreement with an e-invoice operator (Telema, Omniva, Fitek); until then the file is downloaded and
 * handed to the buyer or uploaded to their portal.</p>
 */
@Service
public class EInvoiceXmlService {

    /** The version of the Estonian e-invoice standard this document conforms to. */
    private static final String STANDARD_VERSION = "1.2";

    private static final String XSD_LOCATION = "e-invoice_ver1.2.xsd";

    /**
     * Delivery is a separate charge on a Skladdo invoice, but the e-invoice has no document-level
     * delivery field - it becomes an ordinary untaxed line so that the sum of the lines still equals
     * {@code InvoiceSum}. Order tax is computed per line and excludes delivery, hence the 0% rate.
     */
    private static final String DELIVERY_LINE_DESCRIPTION = "Delivery";

    // Length ceilings from the schema's simple types. Values are truncated rather than rejected: a
    // slightly clipped address is a far better outcome than an invoice that will not export at all.
    private static final int SHORT_TEXT = 20;
    private static final int NORMAL_TEXT = 100;
    private static final int LONG_TEXT = 500;
    private static final int REG = 15;
    private static final int POSTAL_CODE = 10;
    private static final int PAYMENT_DESCRIPTION = 210;

    /**
     * Renders the invoice to e-invoice XML bytes (UTF-8). The buyer side comes from the invoice's own
     * snapshot, the seller side from {@code company} + {@code settings}.
     */
    public byte[] render(Invoice invoice, Company company, CompanySettings settings) {
        Document doc = newDocument();

        Element root = doc.createElement("E_Invoice");
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi",
                XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
        root.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:noNamespaceSchemaLocation",
                XSD_LOCATION);
        doc.appendChild(root);

        List<Line> lines = lines(invoice);
        List<VatGroup> vatGroups = vatGroups(lines, nz(invoice.getTaxAmount()));
        BigDecimal toPay = amountToPay(invoice);

        appendHeader(doc, root, invoice);
        Element invoiceEl = appendInvoice(doc, root, invoice, company);
        appendParties(doc, invoiceEl, invoice, company, settings);
        appendInvoiceInformation(doc, invoiceEl, invoice);
        appendSumGroup(doc, invoiceEl, invoice, lines, vatGroups, toPay);
        appendItems(doc, invoiceEl, invoice, lines);
        appendPaymentInfo(doc, invoiceEl, invoice, company, settings, toPay);
        appendFooter(doc, root, toPay);

        return serialise(doc);
    }

    // ---------------------------------------------------------------------------------------------
    // Document blocks
    // ---------------------------------------------------------------------------------------------

    /** File-level metadata. {@code FileId} lets the receiver detect a file it has already processed. */
    private void appendHeader(Document doc, Element root, Invoice invoice) {
        Element header = child(doc, root, "Header");
        text(doc, header, "Date", LocalDate.now().toString());
        text(doc, header, "FileId", truncate(invoice.getInvoiceNumber(), SHORT_TEXT));
        text(doc, header, "Version", STANDARD_VERSION);
    }

    /**
     * The {@code Invoice} element and its attributes. {@code regNumber} (the buyer's registration code)
     * is required by the schema but routinely unknown for private individuals and small buyers, so it
     * falls back to an empty string - which the type permits - rather than blocking the export.
     */
    private Element appendInvoice(Document doc, Element root, Invoice invoice, Company company) {
        Element el = child(doc, root, "Invoice");
        el.setAttribute("invoiceId", orEmpty(truncate(invoice.getInvoiceNumber(), NORMAL_TEXT)));
        el.setAttribute("regNumber", orEmpty(truncate(invoice.getClientRegistrationCode(), REG)));
        el.setAttribute("sellerRegnumber", orEmpty(truncate(company.getRegistrationCode(), REG)));
        return el;
    }

    /**
     * Seller and buyer blocks. Both follow the schema's order, in which {@code DepId} - the department
     * identifier a buyer's accounting system uses to route the invoice internally - comes directly after
     * {@code Name}.
     */
    private void appendParties(Document doc, Element invoiceEl, Invoice invoice, Company company,
                               CompanySettings settings) {
        Element parties = child(doc, invoiceEl, "InvoiceParties");

        // SellerParty: Name, DepId?, RegNumber, VATRegNumber?, ContactData?, AccountInfo?
        Element seller = child(doc, parties, "SellerParty");
        text(doc, seller, "Name", truncate(company.getName(), NORMAL_TEXT));
        text(doc, seller, "RegNumber", orEmpty(truncate(company.getRegistrationCode(), REG)));
        optional(doc, seller, "VATRegNumber", truncate(settings.getVatNumber(), REG));
        appendContactData(doc, seller, settings.getCompanyPhone(), settings.getCompanyEmail(),
                sellerAddress(settings));
        appendAccountInfo(doc, seller, settings);

        // BuyerParty: Name, DepId?, RegNumber?, VATRegNumber?, ContactData?
        Element buyer = child(doc, parties, "BuyerParty");
        text(doc, buyer, "Name", truncate(invoice.getClientName(), NORMAL_TEXT));
        optional(doc, buyer, "DepId", truncate(invoice.getClientDepartmentId(), NORMAL_TEXT));
        optional(doc, buyer, "RegNumber", truncate(invoice.getClientRegistrationCode(), REG));
        optional(doc, buyer, "VATRegNumber", truncate(invoice.getClientVatNumber(), REG));
        appendContactData(doc, buyer, null, invoice.getClientEmail(), buyerAddress(invoice));
    }

    /**
     * Whether the buyer's address will actually reach the document. Exposed so the readiness check can
     * report a missing address without restating the rule and drifting from what is emitted here.
     */
    public boolean hasUsableBuyerAddress(Invoice invoice) {
        return buyerAddress(invoice).isUsable();
    }

    /** Whether the seller's address will actually reach the document. See {@link #hasUsableBuyerAddress}. */
    public boolean hasUsableSellerAddress(CompanySettings settings) {
        return sellerAddress(settings).isUsable();
    }

    private Address sellerAddress(CompanySettings settings) {
        return new Address(settings.getCompanyAddressStreet(), settings.getCompanyAddressCity(),
                settings.getCompanyAddressPostalCode(), settings.getCompanyCountry());
    }

    /**
     * The buyer's address. Normally the snapshotted parts, but an invoice issued before addresses were
     * split apart has only the single printed line, so that is parsed as a fallback rather than leaving
     * the address off an old document entirely.
     */
    private Address buyerAddress(Invoice invoice) {
        Address snapshot = new Address(invoice.getClientAddressStreet(), invoice.getClientAddressCity(),
                invoice.getClientAddressPostalCode(), invoice.getClientCountry());
        if (snapshot.isUsable()) {
            return snapshot;
        }
        PostalAddress.Split split = PostalAddress.parseLegacy(invoice.getClientAddress());
        return new Address(split.street(), split.city(), null, invoice.getClientCountry());
    }

    /** Contact block, emitted only when there is something to put in it (every child is optional). */
    private void appendContactData(Document doc, Element party, String phone, String email, Address address) {
        boolean hasAddress = address != null && address.isUsable();
        if (isBlank(phone) && isBlank(email) && !hasAddress) {
            return;
        }
        Element contact = child(doc, party, "ContactData");
        optional(doc, contact, "PhoneNumber", truncate(phone, NORMAL_TEXT));
        // The schema spells this element with a hyphen.
        optional(doc, contact, "E-mailAddress", email);
        if (hasAddress) {
            // AddressRecord order: PostalAddress1, PostalAddress2?, City, PostalCode?, Country?
            Element mail = child(doc, contact, "MailAddress");
            text(doc, mail, "PostalAddress1", truncate(address.street(), NORMAL_TEXT));
            text(doc, mail, "City", truncate(address.city(), NORMAL_TEXT));
            optional(doc, mail, "PostalCode", truncate(address.postalCode(), POSTAL_CODE));
            optional(doc, mail, "Country", truncate(address.country(), NORMAL_TEXT));
        }
    }

    /**
     * Seller bank details. {@code AccountNumber} only accepts digits and capitals, so a stored IBAN is
     * normalised (the spaces people type into the settings field would otherwise break the pattern).
     */
    private void appendAccountInfo(Document doc, Element seller, CompanySettings settings) {
        String iban = normaliseAccount(settings.getBankIban());
        if (iban.isEmpty()) {
            return;
        }
        Element account = child(doc, seller, "AccountInfo");
        text(doc, account, "AccountNumber", iban);
        text(doc, account, "IBAN", iban);
        optional(doc, account, "BankName", truncate(settings.getBankName(), NORMAL_TEXT));
    }

    /** Invoice-level metadata: number, dates and payment terms. */
    private void appendInvoiceInformation(Document doc, Element invoiceEl, Invoice invoice) {
        Element info = child(doc, invoiceEl, "InvoiceInformation");

        // CRE marks a credit note; SourceInvoice names the invoice it reverses, which is how the
        // receiving system matches the reversal to the document already in its ledger.
        boolean isCredit = invoice.getType() == InvoiceType.CREDIT;
        Element type = child(doc, info, "Type");
        type.setAttribute("type", isCredit ? "CRE" : "DEB");
        if (isCredit && invoice.getCreditedInvoice() != null) {
            text(doc, type, "SourceInvoice",
                    orEmpty(truncate(invoice.getCreditedInvoice().getInvoiceNumber(), SHORT_TEXT)));
        }

        // The sales order is the agreement the invoice is raised against.
        if (invoice.getSalesOrder() != null) {
            optional(doc, info, "ContractNumber", truncate(invoice.getSalesOrder().getOrderNumber(), NORMAL_TEXT));
        }
        text(doc, info, "DocumentName", documentName(invoice));
        text(doc, info, "InvoiceNumber", orEmpty(truncate(invoice.getInvoiceNumber(), NORMAL_TEXT)));
        text(doc, info, "InvoiceDate", invoice.getIssueDate() != null ? invoice.getIssueDate().toString() : "");
        if (invoice.getDueDate() != null) {
            text(doc, info, "DueDate", invoice.getDueDate().toString());
        }
        // FineRatePerDay is defined as a daily rate, so a penalty accruing on any other cadence cannot
        // be expressed here and is left out rather than misstated.
        BigDecimal penalty = nz(invoice.getPenaltyPercent());
        if (penalty.signum() > 0 && invoice.getPenaltyPeriod() == PenaltyPeriod.DAILY) {
            text(doc, info, "FineRatePerDay", money(penalty));
        }
    }

    /**
     * Document totals and the VAT breakdown. The schema wants one {@code VAT} block per distinct rate;
     * {@code InvoiceSum} is the net of all lines, {@code TotalSum} the gross, and {@code TotalToPay} what
     * the buyer actually owes after any prepayment already invoiced.
     */
    private void appendSumGroup(Document doc, Element invoiceEl, Invoice invoice, List<Line> lines,
                                List<VatGroup> vatGroups, BigDecimal toPay) {
        Element sums = child(doc, invoiceEl, "InvoiceSumGroup");
        String currency = currency(invoice);

        text(doc, sums, "InvoiceSum", money(netTotal(lines)));
        for (VatGroup group : vatGroups) {
            Element vat = child(doc, sums, "VAT");
            text(doc, vat, "SumBeforeVAT", money(group.net));
            text(doc, vat, "VATRate", money(group.rate));
            text(doc, vat, "VATSum", money(group.vat));
            text(doc, vat, "Currency", currency);
        }
        text(doc, sums, "TotalVATSum", money(nz(invoice.getTaxAmount())));
        text(doc, sums, "TotalSum", money(nz(invoice.getTotalAmount())));
        text(doc, sums, "TotalToPay", money(toPay));
        text(doc, sums, "Currency", currency);
    }

    /** Invoice lines. Discounts travel as an {@code Addition} rather than being folded into the price. */
    private void appendItems(Document doc, Element invoiceEl, Invoice invoice, List<Line> lines) {
        Element items = child(doc, invoiceEl, "InvoiceItem");
        Element group = child(doc, items, "InvoiceItemGroup");
        String currency = currency(invoice);

        int rowNo = 1;
        for (Line line : lines) {
            Element entry = child(doc, group, "ItemEntry");
            text(doc, entry, "RowNo", String.valueOf(rowNo++));
            optional(doc, entry, "SellerProductId", truncate(line.sku(), SHORT_TEXT));
            // Description is the one mandatory field on a line and must be non-empty, so an unnamed line
            // falls back to its code and finally to a dash.
            text(doc, entry, "Description", lineDescription(line));

            Element detail = child(doc, entry, "ItemDetailInfo");
            text(doc, detail, "ItemAmount", money(BigDecimal.valueOf(line.quantity())));
            text(doc, detail, "ItemPrice", money(line.unitPrice()));

            // ItemSum is the line before discount; the discount is then stated separately.
            BigDecimal beforeDiscount = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
            text(doc, entry, "ItemSum", money(beforeDiscount));

            BigDecimal discount = scale2(beforeDiscount).subtract(line.net());
            if (discount.signum() > 0) {
                Element addition = child(doc, entry, "Addition");
                addition.setAttribute("addCode", "DSC");
                text(doc, addition, "AddContent", "Discount");
                if (nz(line.discountPercent()).signum() > 0) {
                    text(doc, addition, "AddRate", money(nz(line.discountPercent()).negate()));
                }
                text(doc, addition, "AddSum", money(discount.negate()));
            }

            BigDecimal lineVat = vatOf(line.net(), line.taxRatePercent());
            Element vat = child(doc, entry, "VAT");
            text(doc, vat, "SumBeforeVAT", money(line.net()));
            text(doc, vat, "VATRate", money(nz(line.taxRatePercent())));
            text(doc, vat, "VATSum", money(lineVat));
            text(doc, vat, "Currency", currency);

            text(doc, entry, "ItemTotal", money(line.net().add(lineVat)));
        }
    }

    /**
     * Everything the receiving bank or accounting system needs to raise the payment. Several children are
     * mandatory here, so each falls back to an empty value rather than being omitted.
     */
    private void appendPaymentInfo(Document doc, Element invoiceEl, Invoice invoice, Company company,
                                   CompanySettings settings, BigDecimal toPay) {
        Element payment = child(doc, invoiceEl, "PaymentInfo");
        text(doc, payment, "Currency", currency(invoice));
        // PaymentRefId only accepts digits, which an invoice number with a prefix is not, so the human
        // description carries the reference instead.
        text(doc, payment, "PaymentDescription",
                truncate(paymentDescription(invoice), PAYMENT_DESCRIPTION));
        text(doc, payment, "Payable", invoice.getStatus() == InvoicePaymentStatus.UNPAID ? "YES" : "NO");
        if (invoice.getDueDate() != null) {
            text(doc, payment, "PayDueDate", invoice.getDueDate().toString());
        }
        text(doc, payment, "PaymentTotalSum", money(toPay));
        text(doc, payment, "PayerName", orEmpty(truncate(invoice.getClientName(), NORMAL_TEXT)));
        text(doc, payment, "PaymentId", orEmpty(truncate(invoice.getInvoiceNumber(), NORMAL_TEXT)));
        text(doc, payment, "PayToAccount", normaliseAccount(settings.getBankIban()));
        text(doc, payment, "PayToName", orEmpty(truncate(company.getName(), NORMAL_TEXT)));
    }

    /** File checksums: one invoice per exported file, so the totals describe just that invoice. */
    private void appendFooter(Document doc, Element root, BigDecimal toPay) {
        Element footer = child(doc, root, "Footer");
        text(doc, footer, "TotalNumberInvoices", "1");
        text(doc, footer, "TotalAmount", money(toPay));
    }

    // ---------------------------------------------------------------------------------------------
    // Money
    // ---------------------------------------------------------------------------------------------

    /**
     * The invoice's lines plus, when charged, delivery as a synthetic untaxed line - see
     * {@link #DELIVERY_LINE_DESCRIPTION}.
     */
    private List<Line> lines(Invoice invoice) {
        List<Line> lines = new ArrayList<>();
        for (InvoiceItem item : invoice.getItems()) {
            lines.add(new Line(
                    item.getProductName(),
                    item.getSku(),
                    item.getQuantity() != null ? item.getQuantity() : 0,
                    scale2(nz(item.getUnitPrice())),
                    nz(item.getDiscountPercent()),
                    nz(item.getTaxRatePercent()),
                    scale2(nz(item.getLineTotal()))
            ));
        }
        BigDecimal delivery = scale2(nz(invoice.getDeliveryPrice()));
        if (delivery.signum() > 0) {
            lines.add(new Line(DELIVERY_LINE_DESCRIPTION, null, 1, delivery,
                    BigDecimal.ZERO, BigDecimal.ZERO, delivery));
        }
        return lines;
    }

    /**
     * The VAT breakdown, one entry per distinct rate.
     *
     * <p>Per-rate VAT is recomputed from the lines, but the invoice's own {@code taxAmount} is the figure
     * the buyer was actually billed, and the two can disagree by a cent or two because the order's tax was
     * rounded per line. The difference is absorbed into the largest taxed group so the breakdown always
     * reconciles to the stated total - a receiver that re-adds the blocks and compares them against
     * {@code TotalVATSum} would otherwise reject the document.</p>
     */
    private List<VatGroup> vatGroups(List<Line> lines, BigDecimal snapshotTax) {
        Map<BigDecimal, VatGroup> byRate = new LinkedHashMap<>();
        for (Line line : lines) {
            // Scale-normalised: BigDecimal equality is scale-sensitive, so 20 and 20.00 would otherwise
            // open two separate groups for one rate.
            BigDecimal rate = scale2(nz(line.taxRatePercent()));
            VatGroup group = byRate.computeIfAbsent(rate, VatGroup::new);
            group.net = group.net.add(line.net());
        }

        List<VatGroup> groups = new ArrayList<>(byRate.values());
        BigDecimal computed = BigDecimal.ZERO;
        for (VatGroup group : groups) {
            group.vat = vatOf(group.net, group.rate);
            computed = computed.add(group.vat);
        }

        BigDecimal drift = scale2(snapshotTax).subtract(computed);
        if (drift.signum() != 0) {
            // Only a taxed group can carry the correction; adding VAT to a 0% group would be nonsense.
            groups.stream()
                    .filter(g -> g.rate.signum() > 0)
                    .max(Comparator.comparing(g -> g.net))
                    .ifPresent(g -> g.vat = g.vat.add(drift));
        }
        return groups;
    }

    /**
     * Amount the buyer still owes: the gross total less any prepayment already invoiced, never negative.
     *
     * <p>Always zero for a credit note. Estonian law does not recognise a negative invoice total, so a
     * credit note carries positive amounts with nothing due and the reversal is expressed by its type -
     * the standard is explicit that a credit invoice must state {@code 0.00} here.</p>
     */
    private BigDecimal amountToPay(Invoice invoice) {
        if (invoice.getType() == InvoiceType.CREDIT) {
            return scale2(BigDecimal.ZERO);
        }
        BigDecimal due = nz(invoice.getTotalAmount()).subtract(nz(invoice.getAppliedPrepaymentAmount()));
        return scale2(due.max(BigDecimal.ZERO));
    }

    /** The document's own name, as it is printed. */
    private String documentName(Invoice invoice) {
        return switch (invoice.getType()) {
            case CREDIT -> "Kreeditarve";
            case PREPAYMENT -> "Ettemaksuarve";
            case FINAL -> "Arve";
        };
    }

    private BigDecimal netTotal(List<Line> lines) {
        return lines.stream().map(Line::net).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal vatOf(BigDecimal net, BigDecimal ratePercent) {
        return scale2(net.multiply(nz(ratePercent)).movePointLeft(2));
    }

    /** One VAT rate's running net and tax. Mutable because the breakdown is reconciled after grouping. */
    private static final class VatGroup {
        private final BigDecimal rate;
        private BigDecimal net = BigDecimal.ZERO;
        private BigDecimal vat = BigDecimal.ZERO;

        private VatGroup(BigDecimal rate) {
            this.rate = rate;
        }
    }

    /** A line as the XML needs it: {@code net} is after discount and before tax. */
    private record Line(String description, String sku, int quantity, BigDecimal unitPrice,
                        BigDecimal discountPercent, BigDecimal taxRatePercent, BigDecimal net) {
    }

    /**
     * A postal address ready for the schema's {@code AddressRecord}. The street and city are a mandatory
     * pair there, so a record missing either is not {@link #isUsable} and the whole address block is left
     * out rather than emitted half-filled and rejected.
     */
    private record Address(String street, String city, String postalCode, String country) {

        private boolean isUsable() {
            return !isBlank(street) && !isBlank(city);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DOM and formatting helpers
    // ---------------------------------------------------------------------------------------------

    private Document newDocument() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Could not create the e-invoice XML document", e);
        }
    }

    private byte[] serialise(Document doc) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException | java.io.IOException e) {
            throw new IllegalStateException("Could not serialise the e-invoice XML document", e);
        }
    }

    private static Element child(Document doc, Element parent, String name) {
        Element el = doc.createElement(name);
        parent.appendChild(el);
        return el;
    }

    private static void text(Document doc, Element parent, String name, String value) {
        Element el = child(doc, parent, name);
        el.setTextContent(value != null ? value : "");
    }

    /** Appends the element only when there is a value; used for every optional field. */
    private static void optional(Document doc, Element parent, String name, String value) {
        if (!isBlank(value)) {
            text(doc, parent, name, value);
        }
    }

    private String lineDescription(Line line) {
        if (!isBlank(line.description())) {
            return truncate(line.description(), LONG_TEXT);
        }
        return !isBlank(line.sku()) ? truncate(line.sku(), LONG_TEXT) : "-";
    }

    private String paymentDescription(Invoice invoice) {
        String number = invoice.getInvoiceNumber();
        return isBlank(number) ? "Invoice payment" : "Invoice " + number;
    }

    private String currency(Invoice invoice) {
        String currency = invoice.getCurrency();
        return isBlank(currency) ? "EUR" : currency.trim().toUpperCase();
    }

    /** Strips everything the account pattern (digits and capitals only) would reject. */
    private static String normaliseAccount(String account) {
        return account == null ? "" : account.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
    }

    private static String money(BigDecimal value) {
        return scale2(nz(value)).toPlainString();
    }

    private static BigDecimal scale2(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
