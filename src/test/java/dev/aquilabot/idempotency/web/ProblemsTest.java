package dev.aquilabot.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

class ProblemsTest {

    @Test
    void writesProblemJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Problems.write(response, HttpStatus.CONFLICT, "still \"running\"", "/notes\n");

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,"
                        + "\"detail\":\"still \\\"running\\\"\",\"instance\":\"/notes\\n\"}");
    }

    @Test
    void escapesControlCharacters() {
        assertThat(Problems.quote("a\u0001b\\")).isEqualTo("\"a\\u0001b\\\\\"");
    }
}
