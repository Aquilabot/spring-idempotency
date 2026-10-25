package dev.aquilabot.idempotency.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {

    @Override
    IdempotencyStore createStore() {
        return new InMemoryIdempotencyStore();
    }

    @Test
    void sweepsExpiredEntriesWhileClaiming() {
        InMemoryIdempotencyStore memory = (InMemoryIdempotencyStore) store;
        for (int i = 0; i < InMemoryIdempotencyStore.SWEEP_EVERY - 1; i++) {
            memory.claim(claim("k" + i, "fp", "t", T0));
        }
        assertThat(memory.size()).isEqualTo(InMemoryIdempotencyStore.SWEEP_EVERY - 1);

        memory.claim(claim("last", "fp", "t", T0.plus(Duration.ofHours(25))));

        assertThat(memory.size()).isEqualTo(1);
    }
}
