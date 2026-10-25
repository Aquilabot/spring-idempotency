package dev.aquilabot.idempotency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;

/** Settings under {@code idempotency.*}. */
@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /** Whether the filter is registered at all. */
    private boolean enabled = true;

    /** Request header that carries the key. */
    private String headerName = "Idempotency-Key";

    /** Header added to replayed responses, with the value {@code true}. */
    private String replayedHeaderName = "Idempotent-Replayed";

    /** How long a key is kept after the first request. {@code @Idempotent(ttl = ...)} overrides it. */
    private Duration ttl = Duration.ofHours(24);

    /**
     * How long an in-progress claim blocks other requests with the same key. After that the key can
     * be claimed again, which covers instances that crash mid-request. Keep it above your slowest
     * handler.
     */
    private Duration lockTimeout = Duration.ofMinutes(1);

    /** Longest accepted key, in characters. */
    private int maxKeyLength = 255;

    /** Response headers that are stored and replayed. Everything else, for example Set-Cookie, is dropped. */
    private List<String> storedHeaders = new ArrayList<>(List.of("Content-Type", "Content-Language", "Location", "ETag"));

    /**
     * Path patterns (Spring {@code PathPattern} syntax, for example {@code /api/orders/**}) that are
     * protected without an annotation, for the methods in {@code idempotency.methods}.
     */
    private List<String> pathPatterns = new ArrayList<>();

    /** HTTP methods that {@code idempotency.path-patterns} applies to. */
    private List<String> methods = new ArrayList<>(List.of("POST", "PATCH"));

    /** Whether requests matched by {@code idempotency.path-patterns} must carry a key. */
    private boolean required = true;

    /** Which store to create when the application defines no {@code IdempotencyStore} bean. */
    private StoreType store = StoreType.MEMORY;

    /** Order of the servlet filter. The default runs after Spring Security, so the principal is known. */
    private int filterOrder = Ordered.LOWEST_PRECEDENCE - 100;

    private final Jdbc jdbc = new Jdbc();

    public enum StoreType {
        MEMORY,
        JDBC
    }

    public static class Jdbc {

        /** Table name. Letters, digits and underscores only. */
        private String tableName = "idempotency_keys";

        /** Create the table at startup if it does not exist. Use a migration tool in production. */
        private boolean initializeSchema = false;

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getReplayedHeaderName() {
        return replayedHeaderName;
    }

    public void setReplayedHeaderName(String replayedHeaderName) {
        this.replayedHeaderName = replayedHeaderName;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    public int getMaxKeyLength() {
        return maxKeyLength;
    }

    public void setMaxKeyLength(int maxKeyLength) {
        this.maxKeyLength = maxKeyLength;
    }

    public List<String> getStoredHeaders() {
        return storedHeaders;
    }

    public void setStoredHeaders(List<String> storedHeaders) {
        this.storedHeaders = storedHeaders;
    }

    public List<String> getPathPatterns() {
        return pathPatterns;
    }

    public void setPathPatterns(List<String> pathPatterns) {
        this.pathPatterns = pathPatterns;
    }

    public List<String> getMethods() {
        return methods;
    }

    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public int getFilterOrder() {
        return filterOrder;
    }

    public void setFilterOrder(int filterOrder) {
        this.filterOrder = filterOrder;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }
}
