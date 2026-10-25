package dev.aquilabot.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aquilabot.idempotency.demo.DemoApplication;
import dev.aquilabot.idempotency.demo.HandlerRuns;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts two instances of the demo app on real Tomcat ports. Both use the JDBC store on one
 * PostgreSQL. 50 requests with the same key are spread over both instances.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentRequestsIT {

    static final int REQUESTS = 50;
    static final String BODY = "{\"text\":\"buy milk\"}";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static ConfigurableApplicationContext first;
    static ConfigurableApplicationContext second;

    @BeforeAll
    static void startInstances() throws Exception {
        first = start();
        second = start();
        // Warm up both instances so the first measured request is not slowed by class loading and JIT.
        HttpClient client = HttpClient.newHttpClient();
        for (ConfigurableApplicationContext context : List.of(first, second)) {
            for (int i = 0; i < 3; i++) {
                client.send(request(notesUri(context), "warm-up-" + UUID.randomUUID()), HttpResponse.BodyHandlers.discarding());
            }
            context.getBean(HandlerRuns.class).reset();
        }
    }

    @AfterAll
    static void stopInstances() {
        if (first != null) {
            first.close();
        }
        if (second != null) {
            second.close();
        }
    }

    static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(DemoApplication.class).run(
                "--spring.autoconfigure.exclude=",
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=20",
                // Many JPA setups turn auto-commit off. The store must still commit its own statements.
                "--spring.datasource.hikari.auto-commit=false",
                "--idempotency.store=jdbc",
                "--idempotency.jdbc.initialize-schema=true",
                "--demo.handler-delay-ms=300");
    }

    static URI notesUri(ConfigurableApplicationContext context) {
        return URI.create("http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/notes");
    }

    @BeforeEach
    void resetCounters() {
        first.getBean(HandlerRuns.class).reset();
        second.getBean(HandlerRuns.class).reset();
    }

    /** All 50 requests start at the same moment, while the first one is still running. */
    @Test
    void burstOfRequestsWithOneKeyRunsTheHandlerOnce() throws Exception {
        sendConcurrently("burst-key", 0);
    }

    /** Requests start 10 ms apart over 500 ms, so some arrive after the first one has finished. */
    @Test
    void staggeredRequestsWithOneKeyRunTheHandlerOnce() throws Exception {
        sendConcurrently("staggered-key", 10);
    }

    private void sendConcurrently(String key, long staggerMillis) throws Exception {
        List<URI> instances = List.of(notesUri(first), notesUri(second));
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            long start = System.nanoTime() + Duration.ofMillis(50).toNanos();
            for (int i = 0; i < REQUESTS; i++) {
                URI uri = instances.get(i % 2);
                long startAt = start + Duration.ofMillis(i * staggerMillis).toNanos();
                futures.add(pool.submit(() -> {
                    long wait = startAt - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(Duration.ofNanos(wait));
                    }
                    return client.send(request(uri, key), HttpResponse.BodyHandlers.ofString());
                }));
            }
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
        }

        int handlerRuns = handlerRuns();
        long created = responses.stream().filter(r -> r.statusCode() == 201 && !replayed(r)).count();
        long replays = responses.stream().filter(r -> r.statusCode() == 201 && replayed(r)).count();
        long conflicts = responses.stream().filter(r -> r.statusCode() == 409).count();

        System.out.printf("concurrent=%d stagger=%dms instances=2 handlerRuns=%d replays=%d conflicts=%d%n",
                REQUESTS, staggerMillis, handlerRuns, replays, conflicts);

        assertThat(handlerRuns).isEqualTo(1);
        assertThat(created).isEqualTo(1);
        assertThat(created + replays + conflicts).isEqualTo(REQUESTS);
        String original = responses.stream().filter(r -> r.statusCode() == 201 && !replayed(r))
                .findFirst().orElseThrow().body();
        responses.stream().filter(ConcurrentRequestsIT::replayed)
                .forEach(r -> assertThat(r.body()).isEqualTo(original));
        responses.stream().filter(r -> r.statusCode() == 409)
                .forEach(r -> assertThat(r.headers().firstValue("Content-Type")).hasValue("application/problem+json"));

        // Once the first request has finished, a retry on either instance is a replay.
        for (URI uri : instances) {
            HttpResponse<String> retry = client.send(request(uri, key), HttpResponse.BodyHandlers.ofString());
            assertThat(retry.statusCode()).isEqualTo(201);
            assertThat(replayed(retry)).isTrue();
            assertThat(retry.body()).isEqualTo(original);
        }
        assertThat(handlerRuns()).isEqualTo(1);
    }

    private static int handlerRuns() {
        return first.getBean(HandlerRuns.class).get() + second.getBean(HandlerRuns.class).get();
    }

    static HttpRequest request(URI uri, String key) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "\"" + key + "\"")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();
    }

    static boolean replayed(HttpResponse<String> response) {
        return response.headers().firstValue("Idempotent-Replayed").filter("true"::equals).isPresent();
    }
}
