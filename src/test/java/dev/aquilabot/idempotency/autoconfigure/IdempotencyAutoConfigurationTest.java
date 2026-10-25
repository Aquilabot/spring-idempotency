package dev.aquilabot.idempotency.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aquilabot.idempotency.IdempotencyScopeResolver;
import dev.aquilabot.idempotency.store.IdempotencyStore;
import dev.aquilabot.idempotency.store.InMemoryIdempotencyStore;
import dev.aquilabot.idempotency.store.JdbcIdempotencyStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class IdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));

    @Test
    void defaultsToTheInMemoryStore() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InMemoryIdempotencyStore.class);
            assertThat(context).hasSingleBean(IdempotencyScopeResolver.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("idempotency.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class)
                        .doesNotHaveBean(IdempotencyStore.class));
    }

    @Test
    void usesTheJdbcStoreWhenConfigured() {
        runner.withPropertyValues("idempotency.store=jdbc", "idempotency.jdbc.table-name=api_keys")
                .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:postgresql://localhost:1/unused"))
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcIdempotencyStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryIdempotencyStore.class);
                });
    }

    @Test
    void jdbcStoreWithoutADataSourceFailsAtStartup() {
        runner.withPropertyValues("idempotency.store=jdbc")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void applicationStoreWins() {
        IdempotencyStore custom = new InMemoryIdempotencyStore();
        runner.withPropertyValues("idempotency.store=jdbc")
                .withBean(IdempotencyStore.class, () -> custom)
                .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:postgresql://localhost:1/unused"))
                .run(context -> assertThat(context.getBean(IdempotencyStore.class)).isSameAs(custom));
    }

    @Test
    void notActiveOutsideServletApplications() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }
}
