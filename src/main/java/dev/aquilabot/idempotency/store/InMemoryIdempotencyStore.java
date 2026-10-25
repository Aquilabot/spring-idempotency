package dev.aquilabot.idempotency.store;

import dev.aquilabot.idempotency.store.IdempotencyRecord.State;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps keys in a {@link ConcurrentHashMap}. For tests, development and single-instance apps.
 * Nothing is shared between instances and everything is lost on restart.
 *
 * <p>Expired entries are removed every {@value #SWEEP_EVERY} claims and by {@link #deleteExpired}.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    static final int SWEEP_EVERY = 1000;

    private final ConcurrentMap<Id, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger claims = new AtomicInteger();

    @Override
    public ClaimResult claim(ClaimRequest request) {
        if (claims.incrementAndGet() % SWEEP_EVERY == 0) {
            deleteExpired(request.now());
        }
        AtomicReference<ClaimResult> result = new AtomicReference<>();
        entries.compute(new Id(request.scope(), request.key()), (id, existing) -> {
            if (existing == null || existing.reclaimable(request.now())) {
                result.set(new ClaimResult.Acquired());
                return new Entry(request.fingerprint(), request.token(), State.IN_PROGRESS, null,
                        request.lockedUntil(), request.expiresAt());
            }
            result.set(new ClaimResult.Existing(existing.toRecord()));
            return existing;
        });
        return result.get();
    }

    @Override
    public boolean complete(String scope, String key, String token, StoredResponse response) {
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        entries.computeIfPresent(new Id(scope, key), (id, entry) -> {
            if (!entry.owns(token)) {
                return entry;
            }
            done.set(true);
            return new Entry(entry.fingerprint, entry.token, State.COMPLETED, response, entry.lockedUntil, entry.expiresAt);
        });
        return done.get();
    }

    @Override
    public boolean release(String scope, String key, String token) {
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        entries.computeIfPresent(new Id(scope, key), (id, entry) -> {
            if (!entry.owns(token)) {
                return entry;
            }
            done.set(true);
            return null;
        });
        return done.get();
    }

    @Override
    public int deleteExpired(Instant now) {
        AtomicInteger deleted = new AtomicInteger();
        entries.values().removeIf(entry -> entry.reclaimable(now) && deleted.incrementAndGet() > 0);
        return deleted.get();
    }

    /** Number of stored keys, for tests. */
    public int size() {
        return entries.size();
    }

    private record Id(String scope, String key) {
    }

    private record Entry(String fingerprint, String token, State state, StoredResponse response,
                         Instant lockedUntil, Instant expiresAt) {

        boolean reclaimable(Instant now) {
            return !expiresAt.isAfter(now) || (state == State.IN_PROGRESS && !lockedUntil.isAfter(now));
        }

        boolean owns(String claimToken) {
            return state == State.IN_PROGRESS && token.equals(claimToken);
        }

        IdempotencyRecord toRecord() {
            return new IdempotencyRecord(fingerprint, state, response, expiresAt);
        }
    }
}
