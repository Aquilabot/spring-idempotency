package dev.aquilabot.idempotency.store;

import java.time.Instant;

/**
 * What a store knows about a key.
 *
 * @param fingerprint fingerprint of the request that claimed the key
 * @param state       whether the first request is still running
 * @param response    the stored response, {@code null} while in progress
 * @param expiresAt   when the record is forgotten
 */
public record IdempotencyRecord(String fingerprint, State state, StoredResponse response, Instant expiresAt) {

    public enum State {
        IN_PROGRESS,
        COMPLETED
    }
}
