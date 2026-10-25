package dev.aquilabot.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or every handler method of a controller class) as protected by the
 * {@code Idempotency-Key} request header.
 *
 * <p>The first request with a key runs the handler and stores the response. A retry with the same
 * key and the same request replays the stored response without running the handler.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * Whether requests without the header are rejected with 400. When {@code false}, requests
     * without the header run normally and nothing is stored.
     */
    boolean required() default true;

    /**
     * How long a key is kept, for example {@code "10m"}, {@code "24h"} or {@code "PT1H"}. Empty means
     * {@code idempotency.ttl}.
     */
    String ttl() default "";
}
