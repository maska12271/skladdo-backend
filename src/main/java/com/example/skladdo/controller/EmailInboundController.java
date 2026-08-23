package com.example.skladdo.controller;

import com.example.skladdo.security.MailgunSignatureVerifier;
import com.example.skladdo.service.InboundReplyService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) inbound-reply webhook for Mailgun Inbound Parse. Mailgun POSTs the parsed
 * message as form fields; we verify its HMAC signature before trusting anything, then match the reply to
 * the originating sent email via the token in the Reply-To address.
 *
 * <p>An unknown token or a message we cannot match still returns 200 (there is nothing actionable, and a
 * non-2xx would make Mailgun retry). Only a failed signature check returns 403.</p>
 */
@RestController
@RequestMapping("/api/public/email-inbound")
@Tag(name = "Email Inbound")
public class EmailInboundController {

    private final MailgunSignatureVerifier signatureVerifier;
    private final InboundReplyService inboundReplyService;

    public EmailInboundController(MailgunSignatureVerifier signatureVerifier,
                                  InboundReplyService inboundReplyService) {
        this.signatureVerifier = signatureVerifier;
        this.inboundReplyService = inboundReplyService;
    }

    @PostMapping("/mailgun")
    public ResponseEntity<Void> mailgun(
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String signature,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String sender,
            @RequestParam(name = "stripped-text", required = false) String strippedText,
            @RequestParam(name = "body-plain", required = false) String bodyPlain
    ) {
        if (!signatureVerifier.isValid(timestamp, token, signature)) {
            return ResponseEntity.status(403).build();
        }

        String trackingToken = extractTrackingToken(recipient);
        if (trackingToken != null) {
            String snippet = strippedText != null ? strippedText : bodyPlain;
            inboundReplyService.recordReply(trackingToken, sender, snippet);
        }
        return ResponseEntity.ok().build();
    }

    /** Pulls the tracking token out of a {@code reply+TOKEN@inbound.domain} recipient address. */
    private static String extractTrackingToken(String recipient) {
        if (recipient == null) {
            return null;
        }
        int at = recipient.indexOf('@');
        String localPart = at >= 0 ? recipient.substring(0, at) : recipient;
        int plus = localPart.indexOf('+');
        if (plus < 0 || plus == localPart.length() - 1) {
            return null;
        }
        return localPart.substring(plus + 1);
    }
}
