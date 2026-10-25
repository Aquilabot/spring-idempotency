package dev.aquilabot.idempotency.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A response as it is stored and replayed.
 *
 * @param status  HTTP status code
 * @param headers selected response headers, in order
 * @param body    response body bytes
 */
public record StoredResponse(int status, Map<String, List<String>> headers, byte[] body) {

    public StoredResponse {
        headers = copy(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Headers as {@code name: value} lines, one header value per line. */
    public String encodeHeaders() {
        StringBuilder out = new StringBuilder();
        headers.forEach((name, values) -> values.forEach(value -> out.append(name).append(": ").append(value).append('\n')));
        return out.toString();
    }

    /** Reverse of {@link #encodeHeaders()}. */
    public static Map<String, List<String>> decodeHeaders(String encoded) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return headers;
        }
        for (String line : encoded.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String value = line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            headers.computeIfAbsent(line.substring(0, colon), name -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredResponse that
                && status == that.status
                && headers.equals(that.headers)
                && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * status + headers.hashCode()) + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "StoredResponse[status=" + status + ", headers=" + headers + ", body=" + body.length + " bytes]";
    }
}
