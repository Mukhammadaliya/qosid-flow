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
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
import uz.greenwhite.gateway.oauth2.model.Token;
import uz.greenwhite.gateway.config.HttpProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class HttpRequestService {

    private final WebClient webClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final OAuth2ProviderService oAuth2ProviderService;
    private final HttpProperties httpProperties;

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

    
    /**
     * Send HTTP request with per-endpoint Circuit Breaker protection and OAuth2 support.
     * Each base URL gets its own circuit breaker instance.
     *
     * Order of operations:
     *   1. Resolve OAuth2 token (before CB — token failure is NOT an external API issue)
     *   2. Acquire CB permission (only if we're actually going to make HTTP call)
     *   3. Send HTTP request
     *   4. Record CB result (onSuccess / onError)
     */
    public Mono<ResponseMessage> sendRequest(RequestMessage request) {
        String compositeId = request.getCompositeId();

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

        // 3. Check if Circuit Breaker is OPEN
        try {
            circuitBreaker.acquirePermission();
        } catch (CallNotPermittedException ex) {
            log.warn("Circuit breaker [{}] OPEN — request blocked: {}", cbName, compositeId);
            return Mono.just(buildCircuitBreakerResponse(request, cbName));
        }

        // 4. Send HTTP request — CB tracks only real HTTP outcomes
        long startTime = System.nanoTime();
        String fullUrl = buildFullUrl(request);
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase());

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

                    return buildSuccessResponse(request, status, contentType, entity.getBody());
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
            Token token = oAuth2ProviderService.getToken(request.getOauth2Provider(), request.getCompanyId());
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
     */
    private String buildFullUrl(RequestMessage request) {
        StringBuilder url = new StringBuilder(request.getBaseUrl());

        if (request.getUri() != null && !request.getUri().isEmpty()) {
            url.append(request.getUri());
        }

        if (request.getParams() != null && !request.getParams().isEmpty()) {
            url.append(request.getParams());
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
        return ResponseMessage.builder()
                .companyId(request.getCompanyId())
                .requestId(request.getRequestId())
                .httpStatus(503)
                .errorMessage("Circuit breaker [" + cbName + "] is OPEN: external API unavailable")
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
                .companyId(request.getCompanyId())
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
     * Build success response from HTTP entity
     */
    private ResponseMessage buildSuccessResponse(RequestMessage request, int status,
                                                 String contentType, byte[] body) {
        return ResponseMessage.builder()
                .companyId(request.getCompanyId())
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
                body = respBody;
                MediaType ct = webEx.getHeaders().getContentType();
                contentType = ct != null ? ct.toString() : null;
            }
        }

        return ResponseMessage.builder()
                .companyId(request.getCompanyId())
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
     * Custom exception for OAuth2 token failures
     */
    public static class OAuth2TokenException extends RuntimeException {
        public OAuth2TokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}