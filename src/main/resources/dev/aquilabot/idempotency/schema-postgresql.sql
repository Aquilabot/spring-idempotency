-- Table for JdbcIdempotencyStore. Written for PostgreSQL, tested on PostgreSQL 17.
-- Copy this into your migration tool (Flyway, Liquibase) or set idempotency.jdbc.initialize-schema=true.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    scope           text        NOT NULL,
    idempotency_key text        NOT NULL,
    fingerprint     text        NOT NULL,
    token           text        NOT NULL,
    state           text        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    status          integer,
    headers         text,
    body            bytea,
    created_at      timestamptz NOT NULL,
    locked_until    timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);
