package dev.aquilabot.idempotency.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredResponseTest {

    @Test
    void headersRoundTrip() {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/json"),
                "Link", List.of("<a>; rel=\"x\"", "<b>: colon"));
        StoredResponse response = new StoredResponse(201, headers, "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(StoredResponse.decodeHeaders(response.encodeHeaders())).isEqualTo(headers);
    }

    @Test
    void bodyIsCopied() {
        byte[] body = {1, 2, 3};
        StoredResponse response = new StoredResponse(200, Map.of(), body);
        body[0] = 9;
        response.body()[1] = 9;

        assertThat(response.body()).containsExactly(1, 2, 3);
    }
}
