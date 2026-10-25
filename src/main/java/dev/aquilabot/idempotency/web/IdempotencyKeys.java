package dev.aquilabot.idempotency.web;

import java.util.Optional;

/**
 * Parses the {@code Idempotency-Key} header.
 *
 * <p>The draft defines the value as a Structured Field string, for example
 * {@code Idempotency-Key: "create-order-4711"}. Many clients send the value
 * without quotes, so a bare token is accepted too. Both forms give the same key. The key must be
 * printable ASCII and at most {@code maxLength} characters long.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /** Returns the key, or empty when the header value is not a valid key. */
    public static Optional<String> parse(String headerValue, int maxLength) {
        if (headerValue == null) {
            return Optional.empty();
        }
        String value = headerValue.strip();
        String key;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            key = unquote(value.substring(1, value.length() - 1));
            if (key == null) {
                return Optional.empty();
            }
        } else {
            if (value.indexOf('"') >= 0) {
                return Optional.empty();
            }
            key = value;
        }
        if (key.isEmpty() || key.length() > maxLength || !printableAscii(key)) {
            return Optional.empty();
        }
        return Optional.of(key);
    }

    private static String unquote(String inner) {
        StringBuilder out = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\') {
                if (i + 1 == inner.length()) {
                    return null;
                }
                char next = inner.charAt(++i);
                if (next != '"' && next != '\\') {
                    return null;
                }
                out.append(next);
            } else if (c == '"') {
                return null;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean printableAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
