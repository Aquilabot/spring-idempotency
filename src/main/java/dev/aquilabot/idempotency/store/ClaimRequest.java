package dev.aquilabot.idempotency.store;

import java.time.Instant;
import java.util.Objects;

/**
 * A request to claim a key.
 *
 * @param scope       client the key belongs to, empty for a global scope
 * @param key         the {@code Idempotency-Key} value
 * @param fingerprint hash of method, path and body of the request
 * @param token       random value that identifies this claim in {@code complete} and {@code release}
 * @param now         current time
 * @param lockedUntil until when an in-progress claim blocks other requests
 * @param expiresAt   when the record, completed or not, is forgotten
 */
public record ClaimRequest(String scope, String key, String fingerprint, String token,
                           Instant now, Instant lockedUntil, Instant expiresAt) {

    public ClaimRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lockedUntil, "lockedUntil");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
