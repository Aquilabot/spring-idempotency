package dev.aquilabot.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void acceptsStructuredFieldStrings() {
        assertThat(IdempotencyKeys.parse("\"8e03978e-40d5-43e8-bc93-6894a57f9324\"", 255))
                .contains("8e03978e-40d5-43e8-bc93-6894a57f9324");
        assertThat(IdempotencyKeys.parse("\"a \\\"quoted\\\" \\\\ key\"", 255)).contains("a \"quoted\" \\ key");
    }

    @Test
    void acceptsBareValues() {
        assertThat(IdempotencyKeys.parse("  order-42 ", 255)).contains("order-42");
    }

    @Test
    void rejectsInvalidValues() {
        assertThat(IdempotencyKeys.parse(null, 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("", 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("\"\"", 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("\"bad \\n escape\"", 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("\"trailing\\\"", 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("half\"quoted", 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("tab\there", 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("über", 255)).isEmpty();
        assertThat(IdempotencyKeys.parse("x".repeat(11), 10)).isEmpty();
        assertThat(IdempotencyKeys.parse("x".repeat(10), 10)).isPresent();
    }
}
