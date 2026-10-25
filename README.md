# spring-idempotency

Support for the `Idempotency-Key` request header in Spring Boot MVC. A client retries a POST or
PATCH with the same key, and the server replays the first response instead of running the handler
a second time.

It follows the IETF draft
[The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/).
Error responses are RFC 9457 problem details.

- `@Idempotent` on a controller method or class, or path patterns in configuration
- Stores status, selected headers and body, replays them with `Idempotent-Replayed: true`
- 422 when a key is reused for a different request, 409 while the first request is still running
- Keys expire after a TTL, global or per endpoint
- In-memory store for tests and single instances
- PostgreSQL store with an atomic claim, so the handler runs once across instances
- Spring Boot auto-configuration, properties under `idempotency.*`

Requires Java 21 or later and Spring Boot 4.1. The JDBC store is written for PostgreSQL and tested on
PostgreSQL 17.

## Install

The library is not on Maven Central yet. Build it locally:

```sh
git clone https://github.com/Aquilabot/spring-idempotency.git
cd spring-idempotency
./mvnw install
```

```xml
<dependency>
    <groupId>dev.aquilabot</groupId>
    <artifactId>spring-idempotency</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Usage

```java
@RestController
@RequestMapping("/orders")
class OrdersController {

    @Idempotent
    @PostMapping
    ResponseEntity<Order> create(@RequestBody NewOrder body) {
        Order order = orders.create(body);
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    @Idempotent(required = false, ttl = "1h")
    @PatchMapping("/{id}")
    Order update(@PathVariable long id, @RequestBody OrderChange change) {
        return orders.update(id, change);
    }
}
```

```http
POST /orders HTTP/1.1
Content-Type: application/json
Idempotency-Key: "create-order-4711"

{"item":"notebook","quantity":2}
```

The draft defines the key as a quoted Structured Field string. Bare values such as
`Idempotency-Key: order-42` are accepted too and mean the same key as `"order-42"`.

Without annotations, protect paths by configuration:

```properties
idempotency.path-patterns=/api/orders/**,/api/payouts/**
idempotency.methods=POST,PATCH
```

## Behaviour

| Case | Status | Handler runs? |
|---|---|---|
| First request with a new key | Handler's status | Yes |
| Retry, same key, same method, path and body | Stored status, `Idempotent-Replayed: true` | No |
| Same key, different method, path, query or body | 422 problem+json | No |
| Same key while the first request is still running | 409 problem+json | No |
| No key on an endpoint that requires one | 400 problem+json | No |
| Key that is empty, longer than 255 characters or not printable ASCII | 400 problem+json | No |
| Same key after the TTL has passed | Handler's status | Yes |
| Handler returned 5xx, threw an exception or called `sendError` | Handler's status. The key is released | Yes, again on retry |
| Handler returned 2xx, 3xx or 4xx | Handler's status. The response is stored | No on retry |
| `@Idempotent(required = false)` without a key | Handler's status | Yes, every time |
| Endpoint without the annotation or a matching pattern | Handler's status | Yes, every time |

Why 5xx is released and 4xx is stored: the draft says a server should not keep a result that the
client is expected to retry. A 503 or a timeout to the database is such a result. A 400 for a
missing field is not. Retrying it gives the same 400, so replaying it is correct and cheaper.

A 422 looks like this:

```json
{"type":"about:blank","title":"Unprocessable Content","status":422,
 "detail":"This Idempotency-Key was already used for a different request.","instance":"/orders"}
```

### Fingerprint

The fingerprint is SHA-256 over the method, the path with its query string, and the raw body bytes.
Headers are not part of it. The body is compared byte for byte, so the same JSON with different key
order or whitespace counts as a different request. Most clients serialize a retry identically, so
in practice this is rarely a problem. For `application/x-www-form-urlencoded` and multipart requests
the form fields are hashed instead of the raw body. File parts are not hashed.

### Scope

Keys are unique per scope. The default scope is the authenticated principal's name, or the empty
string for anonymous requests. Two users who send the same key do not see each other's responses.
For API keys or tenants, define a bean:

```java
@Bean
IdempotencyScopeResolver idempotencyScope() {
    return request -> request.getHeader("X-Tenant-Id");
}
```

The filter runs after Spring Security by default, so the principal is known.

### Expiry and locks

Each record has two timestamps. `expires_at` is `idempotency.ttl` after the first request, 24 hours
by default. After that the key is free again. `locked_until` is `idempotency.lock-timeout` after the
first request, 1 minute by default. If the instance that runs the handler crashes, the key stays in
progress until then, and retries get 409. After that, a retry may claim the key and run the handler.

Set `lock-timeout` above your slowest handler. If a handler runs longer, a retry can take the key
over and run the handler a second time. The first instance then logs a warning and does not store
its response.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `idempotency.enabled` | `true` | Register the filter |
| `idempotency.header-name` | `Idempotency-Key` | Request header with the key |
| `idempotency.replayed-header-name` | `Idempotent-Replayed` | Header added to replays |
| `idempotency.ttl` | `24h` | How long keys are kept |
| `idempotency.lock-timeout` | `1m` | How long an in-progress key blocks retries |
| `idempotency.max-key-length` | `255` | Longest accepted key |
| `idempotency.stored-headers` | `Content-Type, Content-Language, Location, ETag` | Headers stored and replayed |
| `idempotency.path-patterns` | empty | Paths protected without the annotation |
| `idempotency.methods` | `POST, PATCH` | Methods that `path-patterns` applies to |
| `idempotency.required` | `true` | Whether `path-patterns` requests need a key |
| `idempotency.store` | `memory` | `memory` or `jdbc` |
| `idempotency.filter-order` | `Ordered.LOWEST_PRECEDENCE - 100` | Servlet filter order |
| `idempotency.jdbc.table-name` | `idempotency_keys` | Table for the JDBC store |
| `idempotency.jdbc.initialize-schema` | `false` | Create the table at startup |

Only headers in `stored-headers` are replayed. `Set-Cookie` and custom headers are dropped on
purpose.

## Storage

`IdempotencyStore` has four methods: `claim`, `complete`, `release` and `deleteExpired`. Define a
bean of that type to use your own store. Redis is a common choice.

**In memory.** `ConcurrentHashMap` with an atomic `compute` per key. Nothing is shared between
instances and keys are lost on restart. It is the default so tests and local runs need no database.
The app logs a line at startup saying so.

**PostgreSQL.** Set `idempotency.store=jdbc`. It uses the application's `DataSource`. Create the
table from
[`schema-postgresql.sql`](src/main/resources/dev/aquilabot/idempotency/schema-postgresql.sql)
with Flyway or Liquibase, or set `idempotency.jdbc.initialize-schema=true`.

The claim is one statement:

```sql
INSERT INTO idempotency_keys AS t
    (scope, idempotency_key, fingerprint, token, state, created_at, locked_until, expires_at)
VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
ON CONFLICT (scope, idempotency_key) DO UPDATE
    SET fingerprint = EXCLUDED.fingerprint, token = EXCLUDED.token, state = 'IN_PROGRESS', ...
    WHERE t.expires_at <= EXCLUDED.created_at
       OR (t.state = 'IN_PROGRESS' AND t.locked_until <= EXCLUDED.created_at)
RETURNING token
```

A returned row means this request owns the key. That happens when no row existed, or when the row
had expired or was an abandoned claim. No row means another request owns the key. The store then
reads that row to decide between replay, 409 and 422. The primary key on `(scope, idempotency_key)`
makes two concurrent inserts conflict, so only one wins. A plain `ON CONFLICT DO NOTHING` would also
be safe, but expired rows would need a separate delete first. The `DO UPDATE ... WHERE` form
takes over an expired row in the same statement.

Each statement runs in its own short transaction. No row lock or transaction is held while the
handler runs. `complete` and `release` match on a random token per claim, so an instance that lost
its claim cannot overwrite or delete the new owner's row.

Times come from the application's `Clock`, not from `now()` in the database. Keep instance clocks in
sync with NTP. Expired rows are ignored by `claim`, but they stay in the table. Call
`store.deleteExpired(Instant.now())` from a `@Scheduled` job, or delete rows with
`expires_at <= now()` from `pg_cron`. There is an index on `expires_at` for that.

## Concurrency test

`ConcurrentRequestsIT` starts two instances of a small demo app on real Tomcat ports. Both use the
JDBC store against one PostgreSQL container. It sends 50 requests with one key, alternating between
the two instances. The handler sleeps 300 ms. Each request is either the one that ran the handler,
a replay, or a 409.

```sh
./mvnw -B verify
```

Results on 2026-10-24, AMD Ryzen 7 5800X3D, Docker 29.9.2, PostgreSQL 17.10 (`postgres:17-alpine`):

```text
concurrent=50 stagger=0ms instances=2 handlerRuns=1 replays=0 conflicts=49
concurrent=50 stagger=10ms instances=2 handlerRuns=1 replays=19 conflicts=30
```

With no stagger, all 50 requests arrive while the first is still running. With a 10 ms stagger,
requests that arrive after the first one finished get a replay. Three runs of the staggered test gave
the same numbers.
The two instances run in one JVM and share nothing but the database. They are separate Spring
contexts with separate filters, stores and Tomcat servers.

## Testing

```sh
./mvnw -B verify
```

`verify` runs the unit and MockMvc tests with Surefire, then the integration tests (`*IT`) with
Failsafe against `postgres:17-alpine` through Testcontainers. Without Docker the integration tests
are skipped.

The MockMvc tests in `IdempotencyFilterTest` cover each row of the behaviour table. They use the
in-memory store and a test `Clock`, so TTL and lock expiry need no sleeps. `IdempotencyStoreContract`
runs the same store tests, including 32 threads claiming one key, against both stores.

## Limitations

- The whole request body is read into memory before the handler runs, and the whole response body
  is held until it is stored. Do not put `@Idempotent` on large uploads or downloads.
- Asynchronous handlers (`Callable`, `DeferredResult`, `CompletableFuture`, `SseEmitter`,
  `StreamingResponseBody`) are rejected with an exception. Their response is written after the
  filter returns, so it cannot be stored.
- The filter finds the handler through `RequestMappingHandlerMapping`. Functional endpoints
  (`RouterFunction`) need `idempotency.path-patterns`.
- That lookup runs once more per request, also for requests that are not protected.
- A response sent with `sendError`, for example a `ResponseStatusException` with Spring's problem
  details turned off, is not stored. Its body is rendered by the container after the filter. The key
  is released instead.
- If storing the response fails, for example because the database is down, the client still gets
  the response. A retry then sees 409 until `lock-timeout` passes, and after that runs the handler
  again.
- If the store is down when a request arrives, `claim` throws and the request fails with 500. The
  handler does not run without a claim.
- Only Spring MVC on servlets. No WebFlux.

## License

MIT, see [LICENSE](LICENSE). Copyright (c) 2026 Jean Carlo Benites Porras.
