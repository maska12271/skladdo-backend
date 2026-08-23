package com.example.skladdo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verifies the HMAC signature Mailgun attaches to Inbound Parse webhook posts, so the public webhook
 * endpoint only trusts payloads that genuinely came from Mailgun. Mailgun signs {@code timestamp + token}
 * with HMAC-SHA256 keyed by the account's webhook signing key.
 *
 * <p>The comparison is constant-time ({@link MessageDigest#isEqual}, not {@code String.equals}) to avoid
 * leaking how much of a forged signature matched via timing. Requests whose timestamp is older than
 * {@link #MAX_AGE_SECONDS} are also rejected, to bound the replay window even for a captured-but-valid
 * signature.</p>
 */
@Component
public class MailgunSignatureVerifier {

    private static final long MAX_AGE_SECONDS = 5 * 60;

    private final byte[] signingKey;

    public MailgunSignatureVerifier(@Value("${app.mailgun.webhook-signing-key}") String signingKey) {
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param timestamp Mailgun's {@code timestamp} form field (epoch seconds as sent)
     * @param token     Mailgun's {@code token} form field
     * @param signature Mailgun's {@code signature} form field (hex-encoded HMAC)
     * @return true only if the signature is valid and the timestamp is recent
     */
    public boolean isValid(String timestamp, String token, String signature) {
        if (timestamp == null || token == null || signature == null) {
            return false;
        }
        if (isStale(timestamp)) {
            return false;
        }
        byte[] expected = hmacSha256(timestamp + token);
        byte[] provided = decodeHex(signature);
        return provided != null && MessageDigest.isEqual(expected, provided);
    }

    private boolean isStale(String timestamp) {
        try {
            long sent = Long.parseLong(timestamp.trim());
            long ageSeconds = Instant.now().getEpochSecond() - sent;
            return ageSeconds > MAX_AGE_SECONDS || ageSeconds < -MAX_AGE_SECONDS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }

    private static byte[] decodeHex(String hex) {
        try {
            return HexFormat.of().parseHex(hex.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
