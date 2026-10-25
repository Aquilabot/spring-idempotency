package dev.aquilabot.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aquilabot.idempotency.demo.DemoApplication;
import dev.aquilabot.idempotency.demo.HandlerRuns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Protecting endpoints by configuration instead of the annotation. */
@SpringBootTest(classes = DemoApplication.class, properties = {
        "idempotency.path-patterns=/legacy/**",
        "idempotency.replayed-header-name=X-Replayed"})
@AutoConfigureMockMvc
class PathPatternTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HandlerRuns runs;

    @BeforeEach
    void setUp() {
        runs.reset();
    }

    @Test
    void matchingPathRequiresAKey() throws Exception {
        mvc.perform(post("/legacy/notes").content("a")).andExpect(status().isBadRequest());
        assertThat(runs.get()).isZero();
    }

    @Test
    void matchingPathIsReplayed() throws Exception {
        mvc.perform(post("/legacy/notes").content("a").header("Idempotency-Key", "legacy-1"))
                .andExpect(status().isOk())
                .andExpect(content().string("legacy 1"));
        mvc.perform(post("/legacy/notes").content("a").header("Idempotency-Key", "legacy-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Replayed", "true"))
                .andExpect(content().string("legacy 1"));
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void otherPathsAreUntouched() throws Exception {
        mvc.perform(post("/notes/unprotected").contentType("application/json").content("{\"text\":\"a\"}"))
                .andExpect(status().isOk());
    }
}
