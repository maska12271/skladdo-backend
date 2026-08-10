package com.example.kladdo.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Set;

/**
 * Strips monetary values out of responses served to a warehouse partner whose connection has
 * {@code canSeePrices = false}.
 *
 * <p><strong>Why this is one filter and not a change to every DTO.</strong> A partner session can reach
 * products, both kinds of order, stock views and the dashboard, and money appears in all of them - on
 * entities serialized directly ({@code Product}, {@code SalesOrder}) as well as on purpose-built DTOs.
 * Editing each one means the next DTO anyone adds silently leaks by default. Filtering the rendered
 * response instead means a new field has to be <em>named</em> here to be hidden, but nothing is missed
 * because someone forgot to touch a file - and the failure mode of the list being incomplete is exactly
 * today's behaviour, so this can only improve on it.</p>
 *
 * <p><strong>It runs for almost no one.</strong> Only a switched-in partner session with price visibility
 * turned off, which is why over-redacting here is safe: hiding one number too many in the one session whose
 * whole point is hiding numbers is not a regression. Every other request is returned untouched, and the
 * body is only rewritten when the response is actually JSON.</p>
 *
 * <p>Note this is <em>display</em> redaction, matching what the flag has always meant. Quantities, names
 * and currency codes stay: a picker still has to be able to do their job.</p>
 */
@RestControllerAdvice
public class PriceRedactionAdvice implements ResponseBodyAdvice<Object> {

    /**
     * Serialized property names carrying money. Deliberately explicit rather than pattern-matched on
     * "price"/"amount", so adding a field is a decision someone makes rather than something a name happens
     * to trigger. Grouped by where they show up.
     */
    private static final Set<String> MONETARY_FIELDS = Set.of(
            // Catalogue
            "price", "lastPurchasePrice",
            // Order lines and totals
            "unitPrice", "lineTotal", "subtotalAmount", "taxAmount", "totalAmount", "deliveryPrice",
            // Invoicing / receivables (not in the partner grant today, but money all the same)
            "amountDue", "penaltyAmount", "penaltyAmountCharged", "appliedPrepaymentAmount",
            // Dashboard and analytics roll-ups
            "revenue", "profit", "totalRevenue", "totalPurchaseCost", "weightedAvgCost",
            "unpaidTotal", "overdueTotal", "penaltyAccruedTotal", "estimatedValue", "offeredPrice");

    private final ObjectMapper objectMapper;

    public PriceRedactionAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Cheap to say yes; beforeBodyWrite does the real filtering, including the content-type check.
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null || !shouldRedact()) {
            return body;
        }
        // Never touch a download (invoice PDFs, uploads) or a plain string body.
        if (body instanceof Resource || body instanceof byte[] || body instanceof CharSequence) {
            return body;
        }
        if (selectedContentType == null || !selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return body;
        }

        JsonNode tree = objectMapper.valueToTree(body);
        redact(tree);
        return tree;
    }

    /** True only inside a client company the operator may not see prices in. */
    private static boolean shouldRedact() {
        try {
            CustomUserDetails user = SecurityUtil.currentUser();
            return user != null && user.isPartnerSession() && !user.isPartnerCanSeePrices();
        } catch (RuntimeException e) {
            // No principal (a public endpoint, or an error response being written) - nothing to redact.
            return false;
        }
    }

    /** Nulls every monetary property anywhere in the tree, however deeply nested. */
    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String field : MONETARY_FIELDS) {
                if (object.has(field)) {
                    object.putNull(field);
                }
            }
            for (JsonNode child : object) {
                redact(child);
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                redact(child);
            }
        }
    }
}
