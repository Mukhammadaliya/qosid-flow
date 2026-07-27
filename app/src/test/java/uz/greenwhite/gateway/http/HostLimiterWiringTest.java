package uz.greenwhite.gateway.http;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import uz.greenwhite.gateway.config.ApiProperties;
import uz.greenwhite.gateway.config.HttpProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The limiter's wiring into the E4 send path, where the dangerous property is permit accounting:
 * a leaked permit throttles its host forever and stays invisible until saturation. These tests
 * pin every release path — completion, error, cancellation (the one only {@code doFinally} can
 * cover) and the CB-rejection short-circuit — plus the refusal behaviour: CB-shaped response,
 * distinct metric, and no HTTP call.
 *
 * <p>Deliberately not {@code MockitoExtension}: the circuit-breaker registry, limiter and
 * metrics are REAL here, because the assertions are about their state after the reactive chain
 * terminates, not about interactions.</p>
 */
class HostLimiterWiringTest {

    /**
     * The limiter is keyed by the circuit-breaker name — the same single naming rule as the
     * circuit itself. Pre-acquiring under this literal key in the tests below is what pins that:
     * a service keying on anything else would not see the occupied permit.
     */
    private static final String HOST_KEY = "cb-example.com";

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final GatewayMetrics metrics = new GatewayMetrics(new SimpleMeterRegistry());

    private HostConcurrencyLimiter limiter;
    private WebClient webClient;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private HttpRequestService serviceWithLimit(int limit, Mono<ResponseEntity<byte[]>> upstream) {
        ApiProperties apiProperties = new ApiProperties();
        apiProperties.setMaxConcurrentPerHost(limit);
        limiter = new HostConcurrencyLimiter(apiProperties);

        webClient = mock(WebClient.class);
        if (upstream != null) {
            WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
            WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
            WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
            WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
            when(webClient.method(any())).thenReturn(uriSpec);
            when(uriSpec.uri(anyString())).thenReturn(bodySpec);
            when(bodySpec.headers(any())).thenReturn(bodySpec);
            when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.toEntity(byte[].class)).thenReturn(upstream);
        }

        HttpProperties httpProperties = mock(HttpProperties.class);
        Mockito.lenient().when(httpProperties.getTimeoutForUrl(anyString())).thenReturn(60_000);

        return new HttpRequestService(webClient, registry,
                mock(OAuth2ProviderService.class), httpProperties, limiter, metrics);
    }

    private RequestMessage request() {
        return RequestMessage.builder()
                .tenantId(100L).requestId(1L)
                .method("POST")
                .baseUrl("https://example.com")
                .uri("/api")
                .body("{}")
                .build();
    }

    @Test
    void refusedPermitAnswersCbShapedResponseWithoutTouchingTheWire() {
        HttpRequestService service = serviceWithLimit(1, null);
        assertThat(limiter.tryAcquire(HOST_KEY)).isTrue(); // occupy the only permit

        ResponseMessage response = service.sendRequest(request()).block();

        // Same shape as a CB rejection — this is what routes it into park-and-recheck
        // with zero consumer changes.
        assertThat(response.getHttpStatus()).isEqualTo(503);
        assertThat(response.getErrorSource()).isEqualTo("CIRCUIT_BREAKER");
        // ...but a distinguishable message and its OWN metric, so Grafana can tell
        // "our backpressure" from "their outage".
        assertThat(response.getErrorMessage()).contains("Host concurrency limit reached");
        assertThat(metrics.getHostLimiterRejected().count()).isEqualTo(1.0);

        // A refusal takes nothing: still only OUR manual acquire in flight, and no HTTP call.
        assertThat(limiter.inFlight(HOST_KEY)).isEqualTo(1);
        verifyNoInteractions(webClient);
    }

    /**
     * The limit enforced end-to-end: a call that is still on the wire holds its permit, and the
     * next call is refused. This is the test a "tryAcquire ignores the limit" mutant fails.
     */
    @Test
    void permitIsHeldWhileACallIsInFlight() {
        HttpRequestService service = serviceWithLimit(1, Mono.never());

        Disposable inFlightCall = service.sendRequest(request()).subscribe();
        try {
            assertThat(limiter.inFlight(HOST_KEY)).isEqualTo(1);

            ResponseMessage second = service.sendRequest(request()).block();

            assertThat(second.getErrorSource()).isEqualTo("CIRCUIT_BREAKER");
            assertThat(second.getErrorMessage()).contains("Host concurrency limit reached");
            assertThat(metrics.getHostLimiterRejected().count()).isEqualTo(1.0);
        } finally {
            inFlightCall.dispose();
        }
    }

    @Test
    void completionReleasesThePermit() {
        HttpRequestService service = serviceWithLimit(1,
                Mono.just(ResponseEntity.ok("ok".getBytes(StandardCharsets.UTF_8))));

        ResponseMessage response = service.sendRequest(request()).block();

        assertThat(response.getHttpStatus()).isEqualTo(200);
        assertThat(limiter.inFlight(HOST_KEY)).isZero();
        assertThat(limiter.tryAcquire(HOST_KEY)).isTrue(); // capacity genuinely restored
    }

    @Test
    void errorReleasesThePermit() {
        HttpRequestService service = serviceWithLimit(1,
                Mono.error(new RuntimeException("connection reset")));

        ResponseMessage response = service.sendRequest(request()).block();

        assertThat(response.getHttpStatus()).isEqualTo(500); // onErrorResume mapped it
        assertThat(limiter.inFlight(HOST_KEY)).isZero();
        assertThat(limiter.tryAcquire(HOST_KEY)).isTrue();
    }

    /**
     * Cancellation is the release path only {@code doFinally} covers — a consumer-side
     * {@code .timeout()} cancels the chain without ever emitting or erroring, so a release hung
     * off {@code map}/{@code onErrorResume} would leak here. This is the test that fails if
     * {@code doFinally} is dropped or replaced with success/error hooks.
     */
    @Test
    void cancellationReleasesThePermit() {
        HttpRequestService service = serviceWithLimit(1, Mono.never());

        Disposable inFlightCall = service.sendRequest(request()).subscribe();
        assertThat(limiter.inFlight(HOST_KEY)).isEqualTo(1);

        inFlightCall.dispose();

        assertThat(limiter.inFlight(HOST_KEY)).isZero();
        assertThat(limiter.tryAcquire(HOST_KEY)).isTrue();
    }

    /**
     * The short-circuit path: the permit is acquired BEFORE {@code acquirePermission()}, so a
     * CB rejection returns a Mono that never reaches the wire — and never reaches
     * {@code doFinally}. The release must happen synchronously in the catch, or every request
     * against an open circuit leaks one permit and the host is throttled shut long before the
     * circuit closes.
     */
    @Test
    void circuitBreakerRejectionReleasesThePermit() {
        HttpRequestService service = serviceWithLimit(1, null);
        registry.circuitBreaker(HOST_KEY).transitionToOpenState();

        ResponseMessage response = service.sendRequest(request()).block();

        assertThat(response.getHttpStatus()).isEqualTo(503);
        assertThat(response.getErrorMessage()).contains("is OPEN"); // the CB cause, not the limiter's
        assertThat(metrics.getHostLimiterRejected().count()).isZero(); // two causes stay distinct
        assertThat(limiter.inFlight(HOST_KEY)).isZero();
        assertThat(limiter.tryAcquire(HOST_KEY)).isTrue();
        verifyNoInteractions(webClient);
    }

    /** The operator escape hatch wired through: {@code <= 0} means no call is ever refused. */
    @Test
    void nonPositiveLimitNeverRefuses() {
        HttpRequestService service = serviceWithLimit(0,
                Mono.just(ResponseEntity.ok("ok".getBytes(StandardCharsets.UTF_8))));

        for (int i = 0; i < 30; i++) { // above the default 25
            assertThat(service.sendRequest(request()).block().getHttpStatus()).isEqualTo(200);
        }
        assertThat(metrics.getHostLimiterRejected().count()).isZero();
        assertThat(limiter.inFlight(HOST_KEY)).isZero(); // and still released 1:1
    }
}
