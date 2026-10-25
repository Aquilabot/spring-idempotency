package dev.aquilabot.idempotency.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aquilabot.idempotency.store.IdempotencyRecord.State;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour every {@link IdempotencyStore} must have. */
abstract class IdempotencyStoreContract {

    static final Instant T0 = Instant.parse("2026-10-24T09:00:00Z");
    static final StoredResponse CREATED = new StoredResponse(201,
            Map.of("Content-Type", List.of("application/json"), "Location", List.of("/notes/1")),
            "{\"id\":1}".getBytes(StandardCharsets.UTF_8));

    IdempotencyStore store;
    String scope;

    abstract IdempotencyStore createStore();

    @BeforeEach
    void setUpStore() {
        store = createStore();
        scope = "client-" + UUID.randomUUID();
    }

    ClaimRequest claim(String key, String fingerprint, String token, Instant now) {
        return new ClaimRequest(scope, key, fingerprint, token, now, now.plus(Duration.ofMinutes(1)),
                now.plus(Duration.ofHours(24)));
    }

    @Test
    void firstClaimAcquiresTheKey() {
        assertThat(store.claim(claim("k", "fp", "t1", T0))).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    void secondClaimSeesTheInProgressRecord() {
        store.claim(claim("k", "fp", "t1", T0));

        ClaimResult second = store.claim(claim("k", "fp-other", "t2", T0.plusSeconds(1)));

        assertThat(second).isInstanceOfSatisfying(ClaimResult.Existing.class, existing -> {
            assertThat(existing.record().state()).isEqualTo(State.IN_PROGRESS);
            assertThat(existing.record().fingerprint()).isEqualTo("fp");
            assertThat(existing.record().response()).isNull();
        });
    }

    @Test
    void completedRecordCarriesTheResponse() {
        store.claim(claim("k", "fp", "t1", T0));
        assertThat(store.complete(scope, "k", "t1", CREATED)).isTrue();

        ClaimResult again = store.claim(claim("k", "fp", "t2", T0.plusSeconds(5)));

        assertThat(again).isInstanceOfSatisfying(ClaimResult.Existing.class, existing -> {
            assertThat(existing.record().state()).isEqualTo(State.COMPLETED);
            assertThat(existing.record().response()).isEqualTo(CREATED);
            assertThat(existing.record().expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
        });
    }

    @Test
    void releasedKeyCanBeClaimedAgain() {
        store.claim(claim("k", "fp", "t1", T0));
        assertThat(store.release(scope, "k", "t1")).isTrue();

        assertThat(store.claim(claim("k", "fp", "t2", T0))).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    void onlyTheOwningTokenCanCompleteOrRelease() {
        store.claim(claim("k", "fp", "t1", T0));

        assertThat(store.complete(scope, "k", "other", CREATED)).isFalse();
        assertThat(store.release(scope, "k", "other")).isFalse();
        assertThat(store.claim(claim("k", "fp", "t2", T0))).isInstanceOf(ClaimResult.Existing.class);
    }

    @Test
    void completedRecordCannotBeReleased() {
        store.claim(claim("k", "fp", "t1", T0));
        store.complete(scope, "k", "t1", CREATED);

        assertThat(store.release(scope, "k", "t1")).isFalse();
        assertThat(store.complete(scope, "k", "t1", CREATED)).isFalse();
    }

    @Test
    void expiredRecordIsTakenOver() {
        store.claim(claim("k", "fp", "t1", T0));
        store.complete(scope, "k", "t1", CREATED);

        Instant later = T0.plus(Duration.ofHours(24));
        assertThat(store.claim(claim("k", "fp-new", "t2", later))).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.claim(claim("k", "fp-new", "t3", later))).isInstanceOfSatisfying(ClaimResult.Existing.class,
                existing -> assertThat(existing.record().fingerprint()).isEqualTo("fp-new"));
    }

    @Test
    void abandonedClaimIsTakenOverAfterTheLockTimeout() {
        store.claim(claim("k", "fp", "t1", T0));

        assertThat(store.claim(claim("k", "fp", "t2", T0.plusSeconds(59)))).isInstanceOf(ClaimResult.Existing.class);
        assertThat(store.claim(claim("k", "fp", "t3", T0.plusSeconds(60)))).isInstanceOf(ClaimResult.Acquired.class);

        assertThat(store.complete(scope, "k", "t1", CREATED)).as("old owner lost the claim").isFalse();
        assertThat(store.complete(scope, "k", "t3", CREATED)).isTrue();
    }

    @Test
    void scopesAreIndependent() {
        store.claim(claim("k", "fp", "t1", T0));

        ClaimRequest otherScope = new ClaimRequest(scope + "-other", "k", "fp", "t2", T0, T0.plusSeconds(60),
                T0.plusSeconds(3600));
        assertThat(store.claim(otherScope)).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredRecords() {
        store.claim(claim("old", "fp", "t1", T0));
        store.complete(scope, "old", "t1", CREATED);
        store.claim(claim("fresh", "fp", "t2", T0.plus(Duration.ofHours(23))));
        store.complete(scope, "fresh", "t2", CREATED);

        assertThat(store.deleteExpired(T0.plus(Duration.ofHours(24)))).isGreaterThanOrEqualTo(1);

        assertThat(store.claim(claim("fresh", "fp", "t3", T0.plus(Duration.ofHours(24)))))
                .isInstanceOf(ClaimResult.Existing.class);
    }

    @Test
    void concurrentClaimsAcquireTheKeyOnce() throws Exception {
        int threads = 32;
        CyclicBarrier start = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<ClaimResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String token = "t" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return store.claim(claim("hot", "fp", token, T0));
                }));
            }
            int acquired = 0;
            for (Future<ClaimResult> result : results) {
                if (result.get() instanceof ClaimResult.Acquired) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        }
    }
}
