package dev.aquilabot.idempotency.store;

import java.time.Instant;

/**
 * Storage for idempotency keys.
 *
 * <p>The contract that matters is {@link #claim}: for one {@code (scope, key)}, at most one caller
 * may get {@link ClaimResult.Acquired} until that claim is completed, released, or expires. That is
 * what makes the handler run once, also across application instances that share the store.
 *
 * <p>All time comparisons use the {@code now} passed in by the caller, never the store's own clock.
 */
public interface IdempotencyStore {

    /**
     * Claims a key atomically. Returns {@link ClaimResult.Acquired} when there was no record, or when
     * the existing record has expired ({@code expiresAt <= now}) or is an abandoned in-progress claim
     * ({@code lockedUntil <= now}). Otherwise returns the existing record.
     */
    ClaimResult claim(ClaimRequest request);

    /**
     * Stores the response for a claim. Returns {@code false} when the claim identified by
     * {@code token} no longer exists, for example because it was taken over after its lock expired.
     */
    boolean complete(String scope, String key, String token, StoredResponse response);

    /**
     * Removes an in-progress claim so the next request with the key runs the handler again.
     * Returns {@code false} when the claim identified by {@code token} no longer exists.
     */
    boolean release(String scope, String key, String token);

    /**
     * Deletes records that have expired and in-progress claims whose lock has expired. Expired
     * records are ignored by {@link #claim} anyway; this only reclaims space. Returns the number of
     * deleted records.
     */
    int deleteExpired(Instant now);
}
