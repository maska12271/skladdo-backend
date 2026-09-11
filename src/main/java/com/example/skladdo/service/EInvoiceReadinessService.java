package com.example.skladdo.service;

import com.example.skladdo.dto.EInvoiceIssueDto;
import com.example.skladdo.model.Company;
import com.example.skladdo.model.CompanySettings;
import com.example.skladdo.model.Invoice;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks what an invoice is missing before it is exported as an e-invoice.
 *
 * <p>{@link EInvoiceXmlService} is deliberately forgiving: a mandatory element with nothing to put in it
 * goes out empty and an unusable address is left off entirely, because refusing to build the document
 * would help nobody. The cost of that is silence - a file with no IBAN cannot be paid from, and the user
 * finds out when the buyer complains. This turns that silence into a list shown before the download.</p>
 *
 * <p>It asks {@link EInvoiceXmlService} directly whether each address would survive, rather than
 * restating the rule, so the warning cannot drift from what is actually written.</p>
 */
@Service
public class EInvoiceReadinessService {

    private final EInvoiceXmlService xmlService;

    public EInvoiceReadinessService(EInvoiceXmlService xmlService) {
        this.xmlService = xmlService;
    }

    /** Everything missing from this invoice, worst first. An empty list means it exports cleanly. */
    public List<EInvoiceIssueDto> check(Invoice invoice, Company company, CompanySettings settings) {
        List<EInvoiceIssueDto> issues = new ArrayList<>();

        // --- Blocking: a mandatory element would be written empty ---------------------------------

        // Carried twice, as the Invoice/@sellerRegnumber attribute and SellerParty/RegNumber.
        if (isBlank(company.getRegistrationCode())) {
            issues.add(EInvoiceIssueDto.blocking("sellerRegistrationCode"));
        }
        // PayToAccount is mandatory, and without it the document cannot be paid from at all.
        if (isBlank(settings.getBankIban())) {
            issues.add(EInvoiceIssueDto.blocking("sellerBankIban"));
        }
        if (isBlank(invoice.getClientName())) {
            issues.add(EInvoiceIssueDto.blocking("buyerName"));
        }

        // --- Warnings: valid, but weaker than receivers generally expect --------------------------

        if (isBlank(settings.getVatNumber())) {
            issues.add(EInvoiceIssueDto.warning("sellerVatNumber"));
        }
        if (!xmlService.hasUsableSellerAddress(settings)) {
            issues.add(EInvoiceIssueDto.warning("sellerAddress"));
        }
        // Goes out as the Invoice/@regNumber attribute; it is how the receiver identifies itself in its
        // own system, so a blank one often means a rejected or manually-handled invoice.
        if (isBlank(invoice.getClientRegistrationCode())) {
            issues.add(EInvoiceIssueDto.warning("buyerRegistrationCode"));
        }
        if (isBlank(invoice.getClientVatNumber())) {
            issues.add(EInvoiceIssueDto.warning("buyerVatNumber"));
        }
        if (!xmlService.hasUsableBuyerAddress(invoice)) {
            issues.add(EInvoiceIssueDto.warning("buyerAddress"));
        }

        return issues;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
