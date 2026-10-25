package dev.aquilabot.idempotency.web;

import dev.aquilabot.idempotency.IdempotencyProperties;
import dev.aquilabot.idempotency.IdempotencyScopeResolver;
import dev.aquilabot.idempotency.Idempotent;
import dev.aquilabot.idempotency.store.ClaimRequest;
import dev.aquilabot.idempotency.store.ClaimResult;
import dev.aquilabot.idempotency.store.IdempotencyRecord;
import dev.aquilabot.idempotency.store.IdempotencyStore;
import dev.aquilabot.idempotency.store.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Applies the {@code Idempotency-Key} rules to handler methods annotated with {@link Idempotent}
 * and to requests matched by {@code idempotency.path-patterns}.
 *
 * <ul>
 *   <li>No key and the key is required: 400.</li>
 *   <li>New key: the handler runs. Other responses are stored, then sent. On 5xx or 429, on an
 *       exception, or when the handler used {@code sendError}, the key is released so a retry runs
 *       the handler again.</li>
 *   <li>Known key, different fingerprint: 422.</li>
 *   <li>Known key, first request still running: 409.</li>
 *   <li>Known key, completed: the stored response is replayed with {@code Idempotent-Replayed: true}.</li>
 * </ul>
 *
 * The filter finds the handler through the application's {@link RequestMappingHandlerMapping} beans,
 * which costs one extra handler lookup per request.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Log log = LogFactory.getLog(IdempotencyFilter.class);

    private static final Set<Class<?>> ASYNC_RETURN_TYPES = Set.of(Callable.class, CompletionStage.class,
            DeferredResult.class, WebAsyncTask.class, ResponseBodyEmitter.class, StreamingResponseBody.class);

    private final IdempotencyStore store;
    private final IdempotencyScopeResolver scopeResolver;
    private final IdempotencyProperties properties;
    private final Clock clock;
    private final Supplier<List<RequestMappingHandlerMapping>> handlerMappings;
    private final List<PathPattern> pathPatterns;
    private final Set<String> pathPatternMethods;
    private final ConcurrentMap<Method, Optional<Policy>> annotationPolicies = new ConcurrentHashMap<>();
    private volatile List<RequestMappingHandlerMapping> resolvedMappings;

    public IdempotencyFilter(IdempotencyStore store, IdempotencyScopeResolver scopeResolver,
                             IdempotencyProperties properties, Clock clock,
                             Supplier<List<RequestMappingHandlerMapping>> handlerMappings) {
        this.store = store;
        this.scopeResolver = scopeResolver;
        this.properties = properties;
        this.clock = clock;
        this.handlerMappings = handlerMappings;
        this.pathPatterns = properties.getPathPatterns().stream().map(PathPatternParser.defaultInstance::parse).toList();
        this.pathPatternMethods = properties.getMethods().stream().map(String::toUpperCase).collect(Collectors.toSet());
    }

    /** Whether a request falls under the rules, and how. */
    record Policy(boolean required, Duration ttl) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Policy policy = policyFor(request);
        if (policy == null) {
            chain.doFilter(request, response);
            return;
        }

        String headerName = properties.getHeaderName();
        String header = request.getHeader(headerName);
        if (header == null) {
            if (policy.required()) {
                Problems.write(response, HttpStatus.BAD_REQUEST,
                        "This request requires an " + headerName + " header.", request.getRequestURI());
            } else {
                chain.doFilter(request, response);
            }
            return;
        }
        Optional<String> parsed = IdempotencyKeys.parse(header, properties.getMaxKeyLength());
        if (parsed.isEmpty()) {
            Problems.write(response, HttpStatus.BAD_REQUEST, "The " + headerName
                    + " header must be 1 to " + properties.getMaxKeyLength() + " printable ASCII characters.",
                    request.getRequestURI());
            return;
        }
        String key = parsed.get();

        HttpServletRequest handlerRequest;
        byte[] fingerprintBody;
        if (isFormOrMultipart(request)) {
            handlerRequest = request;
            fingerprintBody = canonicalParameters(request);
        } else {
            BufferedBodyRequest buffered = new BufferedBodyRequest(request);
            handlerRequest = buffered;
            fingerprintBody = buffered.body();
        }
        String fingerprint = RequestFingerprint.of(request.getMethod(), pathAndQuery(request), fingerprintBody);
        String scope = scopeResolver.resolve(request);
        String token = UUID.randomUUID().toString();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(policy.ttl());
        Instant lockedUntil = min(now.plus(properties.getLockTimeout()), expiresAt);

        ClaimResult result = store.claim(new ClaimRequest(scope, key, fingerprint, token, now, lockedUntil, expiresAt));
        switch (result) {
            case ClaimResult.Existing(IdempotencyRecord existing) ->
                    respondToExisting(existing, fingerprint, request, response);
            case ClaimResult.Acquired() ->
                    runAndStore(handlerRequest, response, chain, scope, key, token);
        }
    }

    private void respondToExisting(IdempotencyRecord existing, String fingerprint, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        if (!existing.fingerprint().equals(fingerprint)) {
            Problems.write(response, HttpStatus.UNPROCESSABLE_CONTENT, "This " + properties.getHeaderName()
                    + " was already used for a different request.", request.getRequestURI());
        } else if (existing.state() == IdempotencyRecord.State.IN_PROGRESS) {
            Problems.write(response, HttpStatus.CONFLICT, "A request with this " + properties.getHeaderName()
                    + " is still being processed. Retry later.", request.getRequestURI());
        } else {
            replay(existing.response(), response);
        }
    }

    private void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.status());
        stored.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader(properties.getReplayedHeaderName(), "true");
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private void runAndStore(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                             String scope, String key, String token) throws ServletException, IOException {
        CapturingResponse capturing = new CapturingResponse(response);
        try {
            chain.doFilter(request, capturing);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            release(scope, key, token);
            throw e;
        }
        try {
            if (isRetryable(capturing.getStatus()) || capturing.errorSent()) {
                release(scope, key, token);
            } else if (!store.complete(scope, key, token, toStored(capturing))) {
                log.warn("Idempotency claim was taken over before the response was stored (key " + key
                        + "). The handler took longer than idempotency.lock-timeout.");
            }
        } catch (RuntimeException e) {
            // The handler has run. Send its response anyway; a retry sees the key as in progress
            // until idempotency.lock-timeout passes.
            log.error("Could not store the response for idempotency key " + key, e);
        } finally {
            capturing.copyBodyToResponse();
        }
    }

    /** 5xx and 429 ask the client to try again later, so the result is not kept. */
    private static boolean isRetryable(int status) {
        return status >= 500 || status == 429;
    }

    private void release(String scope, String key, String token) {
        try {
            store.release(scope, key, token);
        } catch (RuntimeException e) {
            log.error("Could not release idempotency key " + key, e);
        }
    }

    private StoredResponse toStored(CapturingResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : properties.getStoredHeaders()) {
            if (name.equalsIgnoreCase("Content-Type")) {
                if (response.getContentType() != null) {
                    headers.put(name, List.of(response.getContentType()));
                }
            } else if (!response.getHeaders(name).isEmpty()) {
                headers.put(name, List.copyOf(response.getHeaders(name)));
            }
        }
        return new StoredResponse(response.getStatus(), headers, response.getContentAsByteArray());
    }

    // --- Which requests are protected ---

    private Policy policyFor(HttpServletRequest request) {
        Object handler = findHandler(request);
        if (handler instanceof HandlerMethod handlerMethod) {
            Optional<Policy> policy = annotationPolicies.computeIfAbsent(handlerMethod.getMethod(),
                    method -> annotationPolicy(handlerMethod));
            if (policy.isPresent()) {
                return policy.get();
            }
        }
        if (!pathPatterns.isEmpty() && pathPatternMethods.contains(request.getMethod())) {
            PathContainer path = PathContainer.parsePath(pathWithinApplication(request));
            for (PathPattern pattern : pathPatterns) {
                if (pattern.matches(path)) {
                    return new Policy(properties.isRequired(), properties.getTtl());
                }
            }
        }
        return null;
    }

    private Optional<Policy> annotationPolicy(HandlerMethod handlerMethod) {
        Idempotent annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), Idempotent.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), Idempotent.class);
        }
        if (annotation == null) {
            return Optional.empty();
        }
        Class<?> returnType = handlerMethod.getReturnType().getParameterType();
        if (ASYNC_RETURN_TYPES.stream().anyMatch(type -> type.isAssignableFrom(returnType))) {
            throw new IllegalStateException("@Idempotent does not support asynchronous handlers: " + handlerMethod);
        }
        Duration ttl = annotation.ttl().isBlank() ? properties.getTtl() : DurationStyle.detectAndParse(annotation.ttl());
        return Optional.of(new Policy(annotation.required(), ttl));
    }

    private Object findHandler(HttpServletRequest request) {
        RequestPath previous = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request) : null;
        try {
            ServletRequestPathUtils.parseAndCache(request);
            for (RequestMappingHandlerMapping mapping : mappings()) {
                HandlerExecutionChain found;
                try {
                    found = mapping.getHandler(request);
                } catch (Exception e) {
                    // No unique match (for example 405 or 415). The DispatcherServlet reports it.
                    return null;
                }
                if (found != null) {
                    return found.getHandler();
                }
            }
            return null;
        } finally {
            if (previous != null) {
                ServletRequestPathUtils.setParsedRequestPath(previous, request);
            } else {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }

    private List<RequestMappingHandlerMapping> mappings() {
        List<RequestMappingHandlerMapping> mappings = resolvedMappings;
        if (mappings == null) {
            mappings = handlerMappings.get();
            resolvedMappings = mappings;
        }
        return mappings;
    }

    // --- Request details ---

    private static String pathAndQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length()) : uri;
    }

    private static boolean isFormOrMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.startsWith("application/x-www-form-urlencoded") || lower.startsWith("multipart/");
    }

    /** Form fields sorted by name. File parts of multipart requests are not included. */
    private static byte[] canonicalParameters(HttpServletRequest request) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(request.getParameterMap()).forEach((name, values) -> Arrays.stream(values).forEach(value -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
