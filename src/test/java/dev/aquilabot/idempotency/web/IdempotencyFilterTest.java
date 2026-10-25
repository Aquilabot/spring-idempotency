package dev.aquilabot.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aquilabot.idempotency.MutableClock;
import dev.aquilabot.idempotency.demo.DemoApplication;
import dev.aquilabot.idempotency.demo.HandlerRuns;
import dev.aquilabot.idempotency.store.ClaimRequest;
import dev.aquilabot.idempotency.store.ClaimResult;
import dev.aquilabot.idempotency.store.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(classes = {DemoApplication.class, IdempotencyFilterTest.ClockConfig.class})
@AutoConfigureMockMvc
class IdempotencyFilterTest {

    private static final String KEY = "Idempotency-Key";
    private static final String REPLAYED = "Idempotent-Replayed";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-10-24T09:00:00Z"));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    HandlerRuns runs;

    @Autowired
    MutableClock clock;

    @Autowired
    IdempotencyStore store;

    private String key;

    @BeforeEach
    void setUp() {
        runs.reset();
        key = UUID.randomUUID().toString();
    }

    @Test
    void firstRequestRunsTheHandler() throws Exception {
        createNote(key, "buy milk")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/notes/1"))
                .andExpect(header().doesNotExist(REPLAYED))
                .andExpect(jsonPath("$.text").value("buy milk"));

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void retryWithTheSameKeyAndBodyReplaysTheStoredResponse() throws Exception {
        String first = createNote(key, "buy milk").andReturn().getResponse().getContentAsString();

        createNote(key, "buy milk")
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED, "true"))
                .andExpect(header().string("Location", "/notes/1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("X-Handler-Run"))
                .andExpect(content().json(first));

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBodyIs422() throws Exception {
        createNote(key, "buy milk").andExpect(status().isCreated());

        createNote(key, "buy bread")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Unprocessable Content"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("This Idempotency-Key was already used for a different request."))
                .andExpect(jsonPath("$.instance").value("/notes"));

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void sameKeyOnADifferentMethodAndPathIs422() throws Exception {
        createNote(key, "buy milk").andExpect(status().isCreated());

        mvc.perform(json(patch("/notes/1"), "{\"text\":\"buy milk\"}").header(KEY, key))
                .andExpect(status().isUnprocessableContent());

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void sameKeyWhileTheFirstRequestIsInFlightIs409() throws Exception {
        byte[] body = "{\"text\":\"buy milk\"}".getBytes(StandardCharsets.UTF_8);
        Instant now = clock.instant();
        ClaimResult claim = store.claim(new ClaimRequest("", key, RequestFingerprint.of("POST", "/notes", body),
                "first-request", now, now.plusSeconds(60), now.plus(Duration.ofHours(24))));
        assertThat(claim).isInstanceOf(ClaimResult.Acquired.class);

        createNote(key, "buy milk")
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"));

        assertThat(runs.get()).isZero();
    }

    @Test
    void inFlightClaimIsReleasedAfterTheLockTimeout() throws Exception {
        byte[] body = "{\"text\":\"buy milk\"}".getBytes(StandardCharsets.UTF_8);
        Instant now = clock.instant();
        store.claim(new ClaimRequest("", key, RequestFingerprint.of("POST", "/notes", body),
                "crashed-instance", now, now.plusSeconds(60), now.plus(Duration.ofHours(24))));

        clock.advance(Duration.ofSeconds(61));

        createNote(key, "buy milk").andExpect(status().isCreated()).andExpect(header().doesNotExist(REPLAYED));
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void missingKeyIs400() throws Exception {
        mvc.perform(json(post("/notes"), "{\"text\":\"buy milk\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("This request requires an Idempotency-Key header."));

        assertThat(runs.get()).isZero();
    }

    @Test
    void invalidKeysAre400() throws Exception {
        for (String invalid : new String[] {"", "\"\"", "a".repeat(256), "café", "\"unterminated", "two\"quotes\""}) {
            createNote(invalid, "buy milk").andExpect(status().isBadRequest());
        }
        assertThat(runs.get()).isZero();
    }

    @Test
    void quotedAndBareKeysAreTheSameKey() throws Exception {
        createNote("\"" + key + "\"", "buy milk").andExpect(status().isCreated());

        createNote(key, "buy milk").andExpect(header().string(REPLAYED, "true"));
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void keyExpiresAfterTheTtl() throws Exception {
        createNote(key, "buy milk").andExpect(status().isCreated());

        clock.advance(Duration.ofHours(23));
        createNote(key, "buy milk").andExpect(header().string(REPLAYED, "true"));

        clock.advance(Duration.ofHours(1));
        createNote(key, "buy bread")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED))
                .andExpect(jsonPath("$.text").value("buy bread"));
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void annotationTtlOverridesTheDefault() throws Exception {
        mvc.perform(json(post("/notes/short-ttl"), "{\"text\":\"a\"}").header(KEY, key)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(9));
        mvc.perform(json(post("/notes/short-ttl"), "{\"text\":\"a\"}").header(KEY, key))
                .andExpect(header().string(REPLAYED, "true"));

        clock.advance(Duration.ofMinutes(1));
        mvc.perform(json(post("/notes/short-ttl"), "{\"text\":\"a\"}").header(KEY, key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(REPLAYED));
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void endpointsWithoutTheAnnotationAreUntouched() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(json(post("/notes/unprotected"), "{\"text\":\"a\"}").header(KEY, key))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(REPLAYED));
        }
        mvc.perform(json(post("/notes/unprotected"), "{\"text\":\"a\"}")).andExpect(status().isOk());

        assertThat(runs.get()).isEqualTo(3);
    }

    @Test
    void optionalKeyRunsNormallyWithoutAHeader() throws Exception {
        mvc.perform(json(post("/notes/optional"), "{\"text\":\"a\"}")).andExpect(status().isOk());
        mvc.perform(json(post("/notes/optional"), "{\"text\":\"a\"}")).andExpect(status().isOk());
        assertThat(runs.get()).isEqualTo(2);

        mvc.perform(json(post("/notes/optional"), "{\"text\":\"a\"}").header(KEY, key)).andExpect(status().isOk());
        mvc.perform(json(post("/notes/optional"), "{\"text\":\"a\"}").header(KEY, key))
                .andExpect(header().string(REPLAYED, "true"));
        assertThat(runs.get()).isEqualTo(3);
    }

    @Test
    void serverErrorsReleaseTheKey() throws Exception {
        mvc.perform(post("/notes/unavailable").header(KEY, key)).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/notes/unavailable").header(KEY, key))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist(REPLAYED));

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void exceptionsReleaseTheKey() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> mvc.perform(post("/notes/throws").header(KEY, key)))
                    .hasRootCauseMessage("handler failed");
        }
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void clientErrorsAreStoredAndReplayed() throws Exception {
        mvc.perform(post("/notes/rejected").header(KEY, key))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("text is required"));
        mvc.perform(post("/notes/rejected").header(KEY, key))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REPLAYED, "true"))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("text is required"));

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void patchIsProtected() throws Exception {
        mvc.perform(json(patch("/notes/7"), "{\"text\":\"b\"}").header(KEY, key)).andExpect(status().isOk());
        mvc.perform(json(patch("/notes/7"), "{\"text\":\"b\"}").header(KEY, key))
                .andExpect(header().string(REPLAYED, "true"))
                .andExpect(jsonPath("$.id").value(7));
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void annotationOnTheClassProtectsEveryHandler() throws Exception {
        mvc.perform(post("/tasks")).andExpect(status().isBadRequest());
        mvc.perform(post("/tasks").header(KEY, key)).andExpect(status().isCreated());
        mvc.perform(post("/tasks").header(KEY, key))
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED, "true"))
                .andExpect(content().string("task 1"));
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerPrincipal() throws Exception {
        Principal alice = () -> "alice";
        Principal bob = () -> "bob";
        mvc.perform(json(post("/notes"), "{\"text\":\"a\"}").header(KEY, key).principal(alice))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/notes"), "{\"text\":\"b\"}").header(KEY, key).principal(bob))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED));
        mvc.perform(json(post("/notes"), "{\"text\":\"a\"}").header(KEY, key).principal(alice))
                .andExpect(header().string(REPLAYED, "true"));

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void formBodiesAreFingerprintedByTheirFields() throws Exception {
        mvc.perform(post("/notes/form").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("text=a")
                .header(KEY, key)).andExpect(status().isOk()).andExpect(jsonPath("$.text").value("a"));
        mvc.perform(post("/notes/form").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("text=a")
                .header(KEY, key)).andExpect(header().string(REPLAYED, "true"));
        mvc.perform(post("/notes/form").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("text=b")
                .header(KEY, key)).andExpect(status().isUnprocessableContent());

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void asynchronousHandlersAreRejected() {
        assertThatThrownBy(() -> mvc.perform(post("/notes/async").header(KEY, key)))
                .hasStackTraceContaining("@Idempotent does not support asynchronous handlers");
    }

    private ResultActions createNote(String idempotencyKey, String text) throws Exception {
        return mvc.perform(json(post("/notes"), "{\"text\":\"" + text + "\"}").header(KEY, idempotencyKey));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
