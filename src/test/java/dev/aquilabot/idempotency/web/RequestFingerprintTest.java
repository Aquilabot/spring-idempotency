package dev.aquilabot.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private static final byte[] BODY = "{\"text\":\"a\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void isStableHexSha256() {
        String fingerprint = RequestFingerprint.of("POST", "/notes", BODY);
        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]+");
        assertThat(RequestFingerprint.of("POST", "/notes", BODY)).isEqualTo(fingerprint);
    }

    @Test
    void changesWithMethodPathQueryOrBody() {
        String base = RequestFingerprint.of("POST", "/notes", BODY);
        assertThat(RequestFingerprint.of("PATCH", "/notes", BODY)).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("POST", "/notes/1", BODY)).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("POST", "/notes?draft=true", BODY)).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("POST", "/notes", "{\"text\":\"b\"}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(base);
    }

    @Test
    void separatesPathFromBody() {
        assertThat(RequestFingerprint.of("POST", "/a", "b".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(RequestFingerprint.of("POST", "/ab", new byte[0]));
    }
}
