package dev.aquilabot.idempotency.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

/**
 * Writes RFC 9457 problem details. The JSON is built by hand so the library does not depend on a
 * particular Jackson version.
 */
final class Problems {

    static final String CONTENT_TYPE = "application/problem+json";

    private Problems() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String detail, String instance)
            throws IOException {
        String json = "{\"type\":\"about:blank\""
                + ",\"title\":" + quote(status.getReasonPhrase())
                + ",\"status\":" + status.value()
                + ",\"detail\":" + quote(detail)
                + ",\"instance\":" + quote(instance)
                + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status.value());
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
