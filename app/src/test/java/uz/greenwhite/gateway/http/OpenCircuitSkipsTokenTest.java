package uz.greenwhite.gateway.http;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import uz.greenwhite.gateway.config.ApiProperties;
import uz.greenwhite.gateway.config.HttpProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Incident 2026-11-20: smartup.online went into maintenance (503 on every path, token endpoint
 * included). Its circuit was OPEN, yet every request still resolved its OAuth2 token first —
 * the token step ran before the CB check — and each one blocked an Oracle HTTP thread in the
 * token wait loop for up to 15 s. The 20-thread pool filled with requests that were going to be
 * refused anyway, and every other host's traffic was rejected as "pool exhausted".
 *
 * <p>An open circuit must answer before any token work: same CB-shaped response (so the consumer
 * parks it exactly as before), no token call, no HTTP call, no limiter permit held.
 */
class OpenCircuitSkipsTokenTest {

    private static final String HOST_KEY = "cb-example.com";

    /** Mirrors application.yml {@code resilience4j.circuitbreaker.configs.default}. */
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                    .slidingWindowSize(10)
                    .failureRateThreshold(50)
                    .build());
    private final OAuth2ProviderService oauth = mock(OAuth2ProviderService.class);
    private final WebClient webClient = mock(WebClient.class);
    private HostConcurrencyLimiter limiter;

    private HttpRequestService service() {
        ApiProperties apiProperties = new ApiProperties();
        apiProperties.setMaxConcurrentPerHost(1);
        limiter = new HostConcurrencyLimiter(apiProperties);
        HttpProperties httpProperties = mock(HttpProperties.class);
        Mockito.lenient().when(httpProperties.getTimeoutForUrl(anyString())).thenReturn(60_000);
        return new HttpRequestService(webClient, registry, oauth, httpProperties, limiter,
                new GatewayMetrics(new SimpleMeterRegistry()));
    }

    private RequestMessage oauthRequest() {
        return RequestMessage.builder()
                .tenantId(100L).requestId(1L)
                .method("POST")
                .baseUrl("https://example.com")
                .uri("/api")
                .body("{}")
                .oauth2Provider("supplier1")
                .build();
    }

    @Test
    void openCircuitAnswersBeforeAnyTokenWork() {
        HttpRequestService service = service();
        registry.circuitBreaker(HOST_KEY).transitionToOpenState();

        ResponseMessage response = service.sendRequest(oauthRequest()).block();

        assertThat(response.getHttpStatus()).isEqualTo(503);
        assertThat(response.getErrorSource()).isEqualTo("CIRCUIT_BREAKER");
        assertThat(response.getErrorMessage()).contains("is OPEN");
        verifyNoInteractions(oauth);
        verifyNoInteractions(webClient);
        assertThat(limiter.inFlight(HOST_KEY)).isZero();
    }

    // ---- token endpoint down on the API's own host → park (not DLQ), and the circuit learns ----

    /** The chain the real code produces: cache wrapper → client wrapper → WebClient exception. */
    private static RuntimeException tokenFailure(Throwable webClientException) {
        return new RuntimeException("Failed to get OAuth2 token for: supplier1",
                new RuntimeException("Failed to get OAuth2 token", webClientException));
    }

    private static WebClientResponseException statusFrom(int status, String url) {
        return WebClientResponseException.create(status, "x", new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8, new MockClientHttpRequest(HttpMethod.POST, URI.create(url)));
    }

    @Test
    void tokenEndpoint5xxOnTheSameHostIsParkedAndCountedByTheCircuit() {
        HttpRequestService service = service();
        when(oauth.getToken(anyString(), anyLong()))
                .thenThrow(tokenFailure(statusFrom(503, "https://example.com/security/oauth/token")));

        ResponseMessage response = service.sendRequest(oauthRequest()).block();

        // CB-shaped → the consumer parks it until the host accepts traffic again (no DLQ).
        assertThat(response.getHttpStatus()).isEqualTo(503);
        assertThat(response.getErrorSource()).isEqualTo("CIRCUIT_BREAKER");
        assertThat(response.getErrorMessage()).contains("OAuth2 token endpoint unavailable");
        // ...and the circuit counts it, otherwise the park re-check sees CLOSED and spins.
        assertThat(registry.circuitBreaker(HOST_KEY).getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        verifyNoInteractions(webClient);
        assertThat(limiter.inFlight(HOST_KEY)).isZero();
    }

    @Test
    void repeatedTokenOutageOpensTheCircuitSoParkedMessagesWaitInsteadOfSpinning() {
        HttpRequestService service = service();
        when(oauth.getToken(anyString(), anyLong()))
                .thenThrow(tokenFailure(statusFrom(503, "https://example.com/security/oauth/token")));

        for (int i = 0; i < 10; i++) {          // sliding-window-size / minimum calls
            service.sendRequest(oauthRequest()).block();
        }

        assertThat(service.isCircuitOpen(HOST_KEY)).isTrue();
    }

    @Test
    void connectionFailureToTheTokenEndpointOnTheSameHostIsAlsoParked() {
        HttpRequestService service = service();
        when(oauth.getToken(anyString(), anyLong())).thenThrow(tokenFailure(
                new WebClientRequestException(new java.net.ConnectException("refused"), HttpMethod.POST,
                        URI.create("https://example.com/security/oauth/token"), new HttpHeaders())));

        ResponseMessage response = service.sendRequest(oauthRequest()).block();

        assertThat(response.getErrorSource()).isEqualTo("CIRCUIT_BREAKER");
    }

    /** Bad credentials are not an outage: never park, never touch the circuit. */
    @Test
    void tokenEndpoint4xxKeepsTheOldOAuth2Failure() {
        HttpRequestService service = service();
        when(oauth.getToken(anyString(), anyLong()))
                .thenThrow(tokenFailure(statusFrom(401, "https://example.com/security/oauth/token")));

        ResponseMessage response = service.sendRequest(oauthRequest()).block();

        assertThat(response.getErrorSource()).isEqualTo("OAUTH2");
        assertThat(registry.circuitBreaker(HOST_KEY).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    /**
     * A token endpoint on ANOTHER host must not open this API host's circuit — it would block
     * every other provider that shares the API host. Old behaviour there.
     */
    @Test
    void tokenEndpoint5xxOnAnotherHostKeepsTheOldOAuth2Failure() {
        HttpRequestService service = service();
        when(oauth.getToken(anyString(), anyLong()))
                .thenThrow(tokenFailure(statusFrom(503, "https://auth.other.com/token")));

        ResponseMessage response = service.sendRequest(oauthRequest()).block();

        assertThat(response.getErrorSource()).isEqualTo("OAUTH2");
        assertThat(registry.circuitBreaker(HOST_KEY).getMetrics().getNumberOfFailedCalls()).isZero();
    }

    /** No cause at all (e.g. waiter timeout) → unknown → old behaviour, never a guess. */
    @Test
    void tokenFailureWithoutAnHttpCauseKeepsTheOldOAuth2Failure() {
        HttpRequestService service = service();
        when(oauth.getToken(anyString(), anyLong()))
                .thenThrow(new RuntimeException("Failed to get OAuth2 token after waiting: supplier1"));

        ResponseMessage response = service.sendRequest(oauthRequest()).block();

        assertThat(response.getErrorSource()).isEqualTo("OAUTH2");
    }

    @Test
    void forcedOpenCircuitAlsoSkipsTheToken() {
        HttpRequestService service = service();
        registry.circuitBreaker(HOST_KEY).transitionToForcedOpenState();

        ResponseMessage response = service.sendRequest(oauthRequest()).block();

        assertThat(response.getErrorSource()).isEqualTo("CIRCUIT_BREAKER");
        verifyNoInteractions(oauth);
    }
}
