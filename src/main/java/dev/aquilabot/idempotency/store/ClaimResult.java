package dev.aquilabot.idempotency.store;

/** Outcome of {@link IdempotencyStore#claim}. */
public sealed interface ClaimResult {

    /** The caller owns the key and runs the handler. */
    record Acquired() implements ClaimResult {
    }

    /** Another request already holds or completed the key. */
    record Existing(IdempotencyRecord record) implements ClaimResult {
    }
}
