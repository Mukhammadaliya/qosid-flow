package uz.greenwhite.gateway.http;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import uz.greenwhite.gateway.config.ApiProperties;
import uz.greenwhite.gateway.config.HttpProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;

/**
 * Builds a {@link HttpRequestService} whose only real collaborator is the circuit-breaker
 * registry.
 *
 * <p>The state-query methods must read the <b>same</b> registry the send path uses, so a test
 * that stubs the registry (as {@code HttpRequestServiceTest} does with {@code @Mock}) cannot
 * prove anything about circuit state. Here the registry is real and everything else is a mock:
 * the query path touches no HTTP client, no OAuth2 provider and no configuration, so a mock
 * that is never called is exactly the right stand-in — and Mockito's strict-stubs check would
 * flag it if that ever stopped being true.
 */
final class HttpRequestServiceTestFixtures {

    private HttpRequestServiceTestFixtures() {
    }

    /**
     * Constructor argument order mirrors the Lombok {@code @RequiredArgsConstructor} field order
     * in {@link HttpRequestService}; if a collaborator is added there this stops compiling, which
     * is the intended signal.
     */
    static HttpRequestService withRegistry(CircuitBreakerRegistry registry) {
        return new HttpRequestService(
                Mockito.mock(WebClient.class),
                registry,
                Mockito.mock(OAuth2ProviderService.class),
                Mockito.mock(HttpProperties.class),
                // Real limiter at the yaml default: the query path never acquires, and a mock
                // whose tryAcquire answers false would silently reroute any send-path use.
                new HostConcurrencyLimiter(new ApiProperties()),
                Mockito.mock(GatewayMetrics.class));
    }
}
