package dev.aquilabot.idempotency.demo;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Counts how often a handler body actually ran. */
@Component
public class HandlerRuns {

    private final AtomicInteger count = new AtomicInteger();

    public int increment() {
        return count.incrementAndGet();
    }

    public int get() {
        return count.get();
    }

    public void reset() {
        count.set(0);
    }
}
