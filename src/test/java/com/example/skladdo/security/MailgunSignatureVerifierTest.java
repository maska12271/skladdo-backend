package com.example.skladdo.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Mailgun inbound webhook signature check. Pure logic, no Spring context: the test
 * signs payloads with the same key/scheme the verifier expects and asserts accept/reject behaviour.
 */
class MailgunSignatureVerifierTest {

    private static final String KEY = "test-webhook-signing-key";
    private final MailgunSignatureVerifier verifier = new MailgunSignatureVerifier(KEY);

    private static String sign(String timestamp, String token) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + token).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsValidRecentSignature() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String token = "abc123";
        assertTrue(verifier.isValid(ts, token, sign(ts, token)));
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String token = "abc123";
        String good = sign(ts, token);
        String tampered = (good.charAt(0) == 'a' ? "b" : "a") + good.substring(1);
        assertFalse(verifier.isValid(ts, token, tampered));
    }

    @Test
    void rejectsWrongKey() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String token = "abc123";
        // A signature produced with a different key must not verify.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("other-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String wrong = HexFormat.of().formatHex(mac.doFinal((ts + token).getBytes(StandardCharsets.UTF_8)));
        assertFalse(verifier.isValid(ts, token, wrong));
    }

    @Test
    void rejectsStaleTimestamp() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond() - 3600); // an hour old
        String token = "abc123";
        assertFalse(verifier.isValid(ts, token, sign(ts, token)));
    }

    @Test
    void rejectsMissingFields() {
        assertFalse(verifier.isValid(null, "t", "s"));
        assertFalse(verifier.isValid("123", null, "s"));
        assertFalse(verifier.isValid("123", "t", null));
    }
}
