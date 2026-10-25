package dev.aquilabot.idempotency.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over the request method, the path with its query string, and the body bytes. Two
 * requests with the same key and different fingerprints are rejected with 422.
 *
 * <p>Headers are not part of the fingerprint. The body is compared byte for byte, so the same JSON
 * with different whitespace or key order counts as a different request.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String method, String pathAndQuery, byte[] body) {
        MessageDigest digest = sha256();
        digest.update(method.getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) '\n');
        digest.update(pathAndQuery.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(body);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
