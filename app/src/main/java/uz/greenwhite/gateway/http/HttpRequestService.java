package uz.greenwhite.gateway.http;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
import uz.greenwhite.gateway.oauth2.model.Token;
import uz.greenwhite.gateway.config.HttpProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class HttpRequestService {

    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final OAuth2ProviderService oAuth2ProviderService;
    private final HttpProperties httpProperties;
    private final HostConcurrencyLimiter hostLimiter;
    private final GatewayMetrics metrics;

    private final Set<String> registeredEventListeners = ConcurrentHashMap.newKeySet();

    /**
     * Get or create circuit breaker for specific base URL.
     * Each external service gets its own circuit breaker.
     */
    private CircuitBreaker getCircuitBreaker(String baseUrl) {
        // Extract domain from base URL for CB name
        String cbName = extractDomainForCB(baseUrl);

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(cbName);

        // Register event listeners only ONCE per circuit breaker name
        if (registeredEventListeners.add(cbName)) {
            cb.getEventPublisher()
                    .onStateTransition(event ->
                            log.warn("Circuit Breaker [{}] state change: {}", cbName, event.getStateTransition()))
                    .onFailureRateExceeded(event ->
                            log.warn("Circuit Breaker [{}] failure rate exceeded: {}%", cbName, event.getFailureRate()))
                    .onSlowCallRateExceeded(event ->
                            log.warn("Circuit Breaker [{}] slow call rate exceeded: {}%", cbName, event.getSlowCallRate()));
        }

        return cb;
    }

    /**
     * Extract domain from base URL for circuit breaker naming.
     * Examples:
     *   http://api.example.com:8080 -> cb-api.example.com
     *   https://example.com/api/v1  -> cb-example.com
     */
    private String extractDomainForCB(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (host == null) {
                // Fallback: use hashcode if URL is malformed
                return "cb-" + Math.abs(baseUrl.hashCode());
            }
            return "cb-" + host;
        } catch (Exception e) {
            log.warn("Failed to extract domain from URL {}, using hashcode", baseUrl);
            return "cb-" + Math.abs(baseUrl.hashCode());
        }
    }

    // ==================== CIRCUIT STATE QUERIES (read-only) ====================

    /**
     * The circuit this request's host is protected by — the same name the send path resolves.
     *
     * <p>Consumers need to know whether an external API is currently refusing traffic <b>before</b>
     * they hand a message to the send path, so they can hold it back instead of burning a retry
     * attempt on a call that is guaranteed to be rejected. That decision is per-host, and the host
     * is only derivable from the request, so the name has to be exposed rather than recomputed by
     * the caller: a second implementation of the naming rule would drift and the consumer would
     * end up reading a circuit that protects nothing.
     */
    public String circuitBreakerName(RequestMessage request) {
        return extractDomainForCB(request.getBaseUrl());
    }

    /**
     * True only while the circuit refuses calls outright.
     *
     * <p>{@code HALF_OPEN} is deliberately NOT open: resilience4j permits a limited number of probe
     * calls in that state, and a waiting message should be one of them rather than sleeping through
     * the recovery window — treating half-open as open would delay recovery for exactly as long as
     * messages keep arriving.
     *
     * <p>An unknown name reads as not open. The circuit is created lazily by the send path, so a
     * name with no entry means nothing has been sent to that host yet and it cannot be open. This
     * uses {@link CircuitBreakerRegistry#find(String)} rather than
     * {@code circuitBreaker(name)} on purpose: the latter would <i>create</i> a breaker as a side
     * effect of a read, publishing a phantom circuit to metrics and the registry.
     *
     * <p>Read-only and side-effect free — it acquires no permission and records no outcome, so
     * calling it never influences the failure rate that drives the circuit. It reads the same
     * registry {@link #getCircuitBreaker(String)} resolves from, so the query and the send path
     * cannot disagree about state.
     */
    public boolean isCircuitOpen(String cbName) {
        return circuitBreakerRegistry.find(cbName)
                .map(CircuitBreaker::getState)
                .filter(state -> state == CircuitBreaker.State.OPEN
                        || state == CircuitBreaker.State.FORCED_OPEN)
                .isPresent();
    }

    /**
     * Send HTTP request with per-endpoint Circuit Breaker protection and OAuth2 support.
     * Each base URL gets its own circuit breaker instance.
     *
     * Order of operations:
     *   1. Resolve OAuth2 token (before CB — token failure is NOT an external API issue)
     *   2. Acquire host concurrency permit (Phase 2 backpressure — refusal is CB-shaped,
     *      so consumers park-and-recheck it exactly like an open circuit)
     *   3. Acquire CB permission (only if we're actually going to make HTTP call)
     *   4. Send HTTP request
     *   5. Record CB result (onSuccess / onError)
     *
     * Permit accounting is 1:1 by construction — a successful tryAcquire is released on exactly
     * one of three mutually exclusive paths: (a) the CB-rejection catch (synchronous, before the
     * Mono is even built), (b) the defensive catch around request construction (synchronous), or
     * (c) doFinally on the returned Mono, which fires once per subscription on completion, error
     * AND cancellation. The consumers always subscribe exactly once, so no Mono is ever built and
     * then dropped with the permit inside it.
     */
    public Mono<ResponseMessage> sendRequest(RequestMessage request) {
        String compositeId = request.getCompositeId();

        // 0. Validate method BEFORE anything throws synchronously — a sync throw
        //    escapes the Mono and the consumer would ack without DLQ (silent loss).
        if (request.getMethod() == null || request.getMethod().isBlank()) {
            log.error("Invalid HTTP method (null/blank) for {} — permanent failure", compositeId);
            return Mono.just(buildSystemErrorResponse(request, "HTTP method is missing on the request"));
        }

        // 1. Resolve OAuth2 token BEFORE Circuit Breaker
        //    Token failure is an internal issue, not external API failure — CB should not track it
        Map<String, String> headers;
        try {
            headers = addAuthorizationHeader(request);
        } catch (OAuth2TokenException e) {
            log.error("OAuth2 token failed, skipping HTTP call: {}", compositeId);
            return Mono.just(buildOAuth2ErrorResponse(request, e.getMessage()));
        }

        // 2. Get circuit breaker for THIS specific base URL
        CircuitBreaker circuitBreaker = getCircuitBreaker(request.getBaseUrl());
        String cbName = circuitBreaker.getName();

        // 3. Per-host concurrency permit — keyed by the CB name so the limiter and the circuit
        //    agree on what "one host" means (single naming rule, cannot drift). A refusal is
        //    common under load and handled by the park-and-recheck path, hence DEBUG not WARN.
        if (!hostLimiter.tryAcquire(cbName)) {
            metrics.getHostLimiterRejected().increment();
            log.debug("Host limiter [{}] at capacity ({} in flight) — request deferred: {}",
                    cbName, hostLimiter.inFlight(cbName), compositeId);
            return Mono.just(buildHostLimiterResponse(request, cbName));
        }
        // From here the permit is held: exactly one release happens, on exactly one of the
        // paths below (CB-rejection catch / defensive catch / doFinally on the returned Mono).

        // 4. Check if Circuit Breaker is OPEN
        try {
            circuitBreaker.acquirePermission();
        } catch (CallNotPermittedException ex) {
            hostLimiter.release(cbName);
            log.warn("Circuit breaker [{}] OPEN — request blocked: {}", cbName, compositeId);
            return Mono.just(buildCircuitBreakerResponse(request, cbName));
        }

        try {
            return doSendRequest(request, circuitBreaker, headers)
                    // The ONLY release for a Mono that reached the wire: doFinally covers
                    // completion, error and cancellation (e.g. the consumer-side .timeout()),
                    // and fires once per subscription. Without it a permit leaks and throttles
                    // this host forever — invisible until saturation.
                    .doFinally(signal -> hostLimiter.release(cbName));
        } catch (RuntimeException e) {
            // Defensive: a synchronous throw while BUILDING the request (e.g. multipart body
            // assembly on a corrupt file part) escapes the Mono — the permit must not escape
            // with it. Reachable from source data; pinned by HostLimiterWiringTest.
            hostLimiter.release(cbName);
            throw e;
        }
    }

    private Mono<ResponseMessage> doSendRequest(RequestMessage request, CircuitBreaker circuitBreaker,
            Map<String, String> headers) {
        String compositeId = request.getCompositeId();
        String cbName = circuitBreaker.getName();

        // 5. Send HTTP request — CB tracks only real HTTP outcomes
        long startTime = System.nanoTime();
        String fullUrl = buildFullUrl(request);
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase(Locale.ROOT));

        log.info("Sending HTTP request [CB: {}]: {} {} -> {}", cbName, method, fullUrl, compositeId);

        int timeoutMs = httpProperties.getTimeoutForUrl(request.getBaseUrl());

        // Choose body: multipart fallback (S3 failed → Biruni DB) or regular JSON
        WebClient.RequestHeadersSpec<?> requestSpec;
        if (request.getBiruniUploadMode() != null && request.getFileParts() != null) {
            String boundary = "----QosidBoundary" + java.util.UUID.randomUUID().toString().replace("-", "");
            byte[] multipartBody = buildMultipartBody(boundary, request);
            String multipartContentType = "multipart/form-data; boundary=" + boundary;

            log.info("Sending multipart fallback [CB: {}]: {} {} -> {} (DB storage)",
                    cbName, method, fullUrl, compositeId);

            requestSpec = webClient.method(method).uri(fullUrl)
                    .headers(h -> {
                        applyHeaders(h, headers, null);
                        h.setContentType(MediaType.parseMediaType(multipartContentType));
                        h.set("BiruniUpload", request.getBiruniUploadMode());
                    })
                    .bodyValue(multipartBody);
        } else {
            byte[] bodyBytes = request.getBody() != null
                    ? request.getBody().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            requestSpec = webClient.method(method).uri(fullUrl)
                    .headers(h -> applyHeaders(h, headers, request.getContentType()))
                    .bodyValue(bodyBytes);
        }

        return requestSpec.retrieve()
                .toEntity(byte[].class)
                .timeout(java.time.Duration.ofMillis(timeoutMs))
                .map(entity -> {
                    long duration = System.nanoTime() - startTime;
                    int status = entity.getStatusCode().value();
                    circuitBreaker.onSuccess(duration, java.util.concurrent.TimeUnit.NANOSECONDS);

                    log.info("HTTP response [CB: {}]: {} -> status={}, time={}ms",
                            cbName, compositeId, status, duration / 1_000_000);

                    String contentType = entity.getHeaders().getContentType() != null
                            ? entity.getHeaders().getContentType().toString()
                            : MediaType.APPLICATION_JSON_VALUE;

                    byte[] body = decompressIfNeeded(entity.getBody(), entity.getHeaders());
                    return buildSuccessResponse(request, status, contentType, body);
                })
                .onErrorResume(ex -> {
                    long duration = System.nanoTime() - startTime;
                    circuitBreaker.onError(duration, java.util.concurrent.TimeUnit.NANOSECONDS, ex);
                    log.error("HTTP request failed [CB: {}]: {} -> {}", cbName, compositeId, ex.getMessage());
                    return Mono.just(buildErrorResponse(request, ex));
                });
    }

    // ==================== OAUTH2 ====================

    /**
     * Get OAuth2 token and add Authorization header.
     * Throws OAuth2TokenException if token acquisition fails.
     */
    private Map<String, String> addAuthorizationHeader(RequestMessage request) {
        Map<String, String> headers = request.getHeaders() != null
                ? new HashMap<>(request.getHeaders())
                : new HashMap<>();

        if (request.getOauth2Provider() == null) {
            return headers;
        }

        try {
            Token token = oAuth2ProviderService.getToken(request.getOauth2Provider(), request.getTenantId());
            headers.put(HttpHeaders.AUTHORIZATION, token.getAuthorizationHeader());
            log.debug("OAuth2 Authorization header added for provider: {}", request.getOauth2Provider());
        } catch (Exception e) {
            log.error("Failed to get OAuth2 token for provider {}: {}",
                    request.getOauth2Provider(), e.getMessage());
            throw new OAuth2TokenException(
                    "OAuth2 token acquisition failed for provider: " + request.getOauth2Provider(), e);
        }

        return headers;
    }

    // ==================== HELPERS ====================

    /**
     * Build a raw multipart/form-data body with param field + file parts.
     * Used for BiruniUpload DB fallback when S3 is unavailable.
     */
    private static byte[] buildMultipartBody(String boundary, RequestMessage request) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            // param field
            if (request.getBody() != null) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write("Content-Disposition: form-data; name=\"param\"\r\n".getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                out.write(request.getBody().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }

            // file parts
            if (request.getFileParts() != null) {
                for (RequestMessage.FilePart part : request.getFileParts()) {
                    out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Disposition: form-data; name=\"" + part.getFieldName()
                            + "\"; filename=\"" + part.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("Content-Type: " + part.getContentType() + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    out.write(part.getData());
                    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }

            // closing boundary
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build multipart body", e);
        }
    }

    /**
     * Build full URL from base URL, URI path, and query parameters.
     * Package-private for unit tests (query separator logic).
     */
    String buildFullUrl(RequestMessage request) {
        StringBuilder url = new StringBuilder(request.getBaseUrl());

        if (request.getUri() != null && !request.getUri().isEmpty()) {
            url.append(request.getUri());
        }

        if (request.getParams() != null && !request.getParams().isEmpty()) {
            String params = request.getParams();
            // Query ajratkichi: params yalang'och "a=1&b=2" bo'lsa '?' (URL'da allaqachon '?'
            // bo'lsa '&') qo'shiladi; params o'zi '?'/'&' bilan kelsa — hech narsa qo'shilmaydi.
            // Busiz /queue GET so'rovlarida query path'ga yopishib ketardi (/smalla=1).
            //
            // params query string'dan oldin path bo'lagini ham olib kelishi mumkin -- BMB
            // shartnomasi aynan shuni yozadi (bmb_requests.params: "URL parameters including path
            // and query string (ex: /John?year=2025)"). OData jo'natuvchilari shunga tayanadi:
            // ob'ekt nomi kirillcha bo'lgani uchun uri'da tura olmaydi, ba'zi amallarda esa u har
            // so'rovda o'zgaradigan guid saqlaydi, uri esa endpoint'niki va o'zgarmas.
            // Shuning uchun ajratkich faqat yalang'och query'ga qo'shiladi: params ichida '?'
            // bo'lsa u o'z ajratkichini olib kelgan, '/' bilan boshlansa — path davom etyapti.
            boolean carriesOwnSeparator = params.startsWith("?")
                    || params.startsWith("&")
                    || params.startsWith("/")
                    || params.indexOf('?') >= 0;
            if (!carriesOwnSeparator) {
                url.append(url.indexOf("?") >= 0 ? '&' : '?');
            }
            url.append(params);
        }

        return url.toString();
    }

    /**
     * Apply custom headers to the HTTP request and decide on Content-Type.
     * Precedence: caller-supplied request content-type → header already present
     * on the HTTP headers → default {@code application/json}.
     */
    private void applyHeaders(HttpHeaders httpHeaders, Map<String, String> customHeaders, String requestContentType) {
        // Add custom headers first
        if (customHeaders != null) {
            customHeaders.forEach(httpHeaders::add);
        }

        if (requestContentType != null && !requestContentType.isBlank()) {
            try {
                httpHeaders.setContentType(MediaType.parseMediaType(requestContentType));
                return;
            } catch (Exception e) {
                log.warn("Unparseable request content-type '{}', falling back to default", requestContentType);
            }
        }

        // Set default Content-Type only if not provided
        if (httpHeaders.getContentType() == null) {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        }
    }

    /**
     * Build response for when Circuit Breaker is OPEN (503).
     */
    private ResponseMessage buildCircuitBreakerResponse(RequestMessage request, String cbName) {
        return buildCbShapedResponse(request,
                "Circuit breaker [" + cbName + "] is OPEN: external API unavailable");
    }

    /**
     * Build response for a refused host-concurrency permit. Same SHAPE as
     * {@link #buildCircuitBreakerResponse} on purpose ({@code errorSource=CIRCUIT_BREAKER},
     * 503): the consumers' park-and-recheck branch keys on that shape, so a deferred call is
     * absorbed with no consumer changes at all. Only the message text and the metric
     * ({@code gateway.http.host.limiter.rejected} vs the circuit's own) tell the two causes
     * apart.
     */
    private ResponseMessage buildHostLimiterResponse(RequestMessage request, String cbName) {
        return buildCbShapedResponse(request,
                "Host concurrency limit reached for [" + cbName + "]: request deferred");
    }

    /** The one CB-blocked shape both causes share — a single builder so they cannot diverge. */
    private ResponseMessage buildCbShapedResponse(RequestMessage request, String errorMessage) {
        return ResponseMessage.builder()
                .tenantId(request.getTenantId())
                .requestId(request.getRequestId())
                .httpStatus(503)
                .errorMessage(errorMessage)
                .errorSource("CIRCUIT_BREAKER")
                .processedAt(LocalDateTime.now())
                .callbackUrl(request.getCallbackUrl())
                .source(request.getSource())
                .build();
    }

    /**
     * Build response for OAuth2 token acquisition failure (401).
     */
    private ResponseMessage buildOAuth2ErrorResponse(RequestMessage request, String errorMessage) {
        return ResponseMessage.builder()
                .tenantId(request.getTenantId())
                .requestId(request.getRequestId())
                .httpStatus(401)
                .errorMessage("OAuth2 authentication failed: " + errorMessage)
                .errorSource("OAUTH2")
                .processedAt(LocalDateTime.now())
                .callbackUrl(request.getCallbackUrl())
                .source(request.getSource())
                .build();
    }

    /**
     * Build response for internal/system failures detected before the HTTP call (status 0).
     * errorSource=SYSTEM + non-retryable status routes this to permanent failure + DLQ.
     */
    private ResponseMessage buildSystemErrorResponse(RequestMessage request, String errorMessage) {
        return ResponseMessage.builder()
                .tenantId(request.getTenantId())
                .requestId(request.getRequestId())
                .httpStatus(0)
                .errorMessage(errorMessage)
                .errorSource("SYSTEM")
                .processedAt(LocalDateTime.now())
                .callbackUrl(request.getCallbackUrl())
                .source(request.getSource())
                .build();
    }

    /**
     * Build success response from HTTP entity
     */
    private ResponseMessage buildSuccessResponse(RequestMessage request, int status,
                                                 String contentType, byte[] body) {
        return ResponseMessage.builder()
                .tenantId(request.getTenantId())
                .requestId(request.getRequestId())
                .httpStatus(status)
                .contentType(contentType)
                .body(body)
                .processedAt(LocalDateTime.now())
                .callbackUrl(request.getCallbackUrl())
                .source(request.getSource())
                .build();
    }

    /**
     * Build error response from exception.
     * When upstream returned an HTTP error with a body, capture it so clients can
     * retrieve the real error payload via {@code /qosid/api/v1/requests/{id}/result}.
     */
    private ResponseMessage buildErrorResponse(RequestMessage request, Throwable ex) {
        int status = 500;
        String errorMessage = ex.getMessage();
        byte[] body = null;
        String contentType = null;

        if (ex instanceof WebClientResponseException webEx) {
            status = webEx.getStatusCode().value();
            errorMessage = "HTTP " + status + ": " + webEx.getStatusText();
            byte[] respBody = webEx.getResponseBodyAsByteArray();
            if (respBody != null && respBody.length > 0) {
                body = decompressIfNeeded(respBody, webEx.getHeaders());
                MediaType ct = webEx.getHeaders().getContentType();
                contentType = ct != null ? ct.toString() : null;
            }
        }

        return ResponseMessage.builder()
                .tenantId(request.getTenantId())
                .requestId(request.getRequestId())
                .httpStatus(status)
                .contentType(contentType)
                .body(body)
                .errorMessage(errorMessage)
                .errorSource("HTTP")
                .processedAt(LocalDateTime.now())
                .callbackUrl(request.getCallbackUrl())
                .source(request.getSource())
                .build();
    }

    /**
     * Decompress the response body if the upstream set {@code Content-Encoding}.
     * Handles gzip and zlib-wrapped deflate. If the header is absent or names an
     * unknown encoding we pass the bytes through. On decompression failure we log
     * and return the original bytes so the caller still has something to store.
     */
    private static byte[] decompressIfNeeded(byte[] body, HttpHeaders headers) {
        if (body == null || body.length == 0 || headers == null) {
            return body;
        }
        String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding == null || encoding.isBlank()) {
            return body;
        }
        String normalized = encoding.trim().toLowerCase(Locale.ROOT);
        try {
            if ("gzip".equals(normalized) || "x-gzip".equals(normalized)) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    return in.readAllBytes();
                }
            }
            if ("deflate".equals(normalized)) {
                try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(body))) {
                    return in.readAllBytes();
                }
            }
            log.debug("Unknown Content-Encoding '{}', passing body through", encoding);
        } catch (IOException e) {
            log.warn("Failed to decompress response (Content-Encoding: {}): {}", encoding, e.getMessage());
        }
        return body;
    }

    /**
     * Custom exception for OAuth2 token failures
     */
    public static class OAuth2TokenException extends RuntimeException {
        public OAuth2TokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}