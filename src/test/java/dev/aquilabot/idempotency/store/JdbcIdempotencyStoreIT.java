package dev.aquilabot.idempotency.store;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the store contract against PostgreSQL. Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyStoreIT extends IdempotencyStoreContract {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static PGSimpleDataSource dataSource;

    @BeforeAll
    static void createSchema() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        JdbcIdempotencyStore store = new JdbcIdempotencyStore(dataSource);
        store.createSchema();
        store.createSchema(); // idempotent
    }

    @Override
    IdempotencyStore createStore() {
        return new JdbcIdempotencyStore(dataSource);
    }

    @Test
    void customTableName() {
        JdbcIdempotencyStore custom = new JdbcIdempotencyStore(dataSource, "custom_keys");
        custom.createSchema();
        store = custom;
        firstClaimAcquiresTheKey();
        secondClaimSeesTheInProgressRecord();
    }

    @Test
    void rejectsUnsafeTableNames() {
        assertThatThrownBy(() -> new JdbcIdempotencyStore(dataSource, "keys; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
