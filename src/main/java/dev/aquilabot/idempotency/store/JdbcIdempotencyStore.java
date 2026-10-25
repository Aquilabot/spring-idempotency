package dev.aquilabot.idempotency.store;

import dev.aquilabot.idempotency.store.IdempotencyRecord.State;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

/**
 * Stores keys in a PostgreSQL table, so all application instances that share the database also
 * share the keys. The schema is in {@value #SCHEMA_LOCATION}.
 *
 * <p>The claim is one statement: an {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE} that only
 * overwrites a row that has expired or whose in-progress lock has expired. When it returns a row,
 * this caller owns the key. When it returns nothing, another request owns the key and its row is
 * read back. Every statement runs in its own short transaction, started here with
 * {@code REQUIRES_NEW}, so it commits even when the pool has auto-commit turned off or the caller is
 * inside another transaction. No lock is held while the handler runs.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    public static final String DEFAULT_TABLE = "idempotency_keys";
    public static final String SCHEMA_LOCATION = "dev/aquilabot/idempotency/schema-postgresql.sql";

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final int CLAIM_ATTEMPTS = 3;

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String table;
    private final String claimSql;
    private final String selectSql;
    private final String completeSql;
    private final String releaseSql;
    private final String deleteExpiredSql;

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public JdbcIdempotencyStore(DataSource dataSource, String table) {
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.table = table;
        this.claimSql = """
                INSERT INTO %1$s AS t
                    (scope, idempotency_key, fingerprint, token, state, created_at, locked_until, expires_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
                ON CONFLICT (scope, idempotency_key) DO UPDATE
                    SET fingerprint = EXCLUDED.fingerprint,
                        token = EXCLUDED.token,
                        state = 'IN_PROGRESS',
                        status = NULL,
                        headers = NULL,
                        body = NULL,
                        created_at = EXCLUDED.created_at,
                        locked_until = EXCLUDED.locked_until,
                        expires_at = EXCLUDED.expires_at
                    WHERE t.expires_at <= EXCLUDED.created_at
                       OR (t.state = 'IN_PROGRESS' AND t.locked_until <= EXCLUDED.created_at)
                RETURNING token
                """.formatted(table);
        this.selectSql = """
                SELECT fingerprint, state, status, headers, body, expires_at
                FROM %s WHERE scope = ? AND idempotency_key = ?
                """.formatted(table);
        this.completeSql = """
                UPDATE %s SET state = 'COMPLETED', status = ?, headers = ?, body = ?
                WHERE scope = ? AND idempotency_key = ? AND token = ? AND state = 'IN_PROGRESS'
                """.formatted(table);
        this.releaseSql = """
                DELETE FROM %s
                WHERE scope = ? AND idempotency_key = ? AND token = ? AND state = 'IN_PROGRESS'
                """.formatted(table);
        this.deleteExpiredSql = """
                DELETE FROM %s
                WHERE expires_at <= ? OR (state = 'IN_PROGRESS' AND locked_until <= ?)
                """.formatted(table);
    }

    /** Runs the bundled schema script, with the table name replaced. Safe to run more than once. */
    public void createSchema() {
        try (var in = new ClassPathResource(SCHEMA_LOCATION).getInputStream()) {
            String script = StreamUtils.copyToString(in, StandardCharsets.UTF_8).replace(DEFAULT_TABLE, table);
            ResourceDatabasePopulator populator =
                    new ResourceDatabasePopulator(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
            tx.executeWithoutResult(status -> populator.execute(dataSource));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + SCHEMA_LOCATION, e);
        }
    }

    @Override
    public ClaimResult claim(ClaimRequest request) {
        for (int attempt = 1; attempt <= CLAIM_ATTEMPTS; attempt++) {
            List<String> owned = tx.execute(status -> jdbc.sql(claimSql)
                    .params(request.scope(), request.key(), request.fingerprint(), request.token(),
                            utc(request.now()), utc(request.lockedUntil()), utc(request.expiresAt()))
                    .query(String.class)
                    .list());
            if (!owned.isEmpty()) {
                return new ClaimResult.Acquired();
            }
            Optional<IdempotencyRecord> existing = tx.execute(status -> jdbc.sql(selectSql)
                    .params(request.scope(), request.key())
                    .query(this::mapRecord)
                    .optional());
            if (existing.isPresent()) {
                return new ClaimResult.Existing(existing.get());
            }
            // The owner released the key between our insert and our read. Try again.
        }
        // Still racing after several attempts: report the key as busy, the client retries later.
        return new ClaimResult.Existing(new IdempotencyRecord(request.fingerprint(), State.IN_PROGRESS, null,
                request.expiresAt()));
    }

    @Override
    public boolean complete(String scope, String key, String token, StoredResponse response) {
        return update(completeSql, response.status(), response.encodeHeaders(), response.body(), scope, key, token) == 1;
    }

    @Override
    public boolean release(String scope, String key, String token) {
        return update(releaseSql, scope, key, token) == 1;
    }

    @Override
    public int deleteExpired(Instant now) {
        return update(deleteExpiredSql, utc(now), utc(now));
    }

    private int update(String sql, Object... params) {
        Integer rows = tx.execute(status -> jdbc.sql(sql).params(params).update());
        return rows == null ? 0 : rows;
    }

    private IdempotencyRecord mapRecord(ResultSet rs, int row) throws SQLException {
        State state = State.valueOf(rs.getString("state"));
        StoredResponse response = state == State.COMPLETED
                ? new StoredResponse(rs.getInt("status"), StoredResponse.decodeHeaders(rs.getString("headers")),
                        rs.getBytes("body"))
                : null;
        return new IdempotencyRecord(rs.getString("fingerprint"), state, response,
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
