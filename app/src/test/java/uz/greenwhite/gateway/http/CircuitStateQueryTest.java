package uz.greenwhite.gateway.http;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.model.kafka.RequestMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumers decide how long to wait out a CB window, so they must be able to read the
 * circuit's state without making a call. HALF_OPEN deliberately reads as "not open": the
 * circuit is allowing probe calls, so the message should go straight through.
 */
class CircuitStateQueryTest {

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

    private HttpRequestService serviceWith(CircuitBreakerRegistry registry) {
        return HttpRequestServiceTestFixtures.withRegistry(registry);
    }

    @Test
    void circuitBreakerNameIsDerivedFromTheHost() {
        HttpRequestService service = serviceWith(registry);
        RequestMessage request = RequestMessage.builder()
                .tenantId(100L).requestId(1L)
                .method("POST")
                .baseUrl("https://smartup.online")
                .uri("/b/trade/api/sm24:order_export")
                .build();

        assertThat(service.circuitBreakerName(request)).isEqualTo("cb-smartup.online");
    }

    @Test
    void openCircuitReadsAsOpen() {
        CircuitBreaker cb = registry.circuitBreaker("cb-smartup.online");
        cb.transitionToOpenState();

        assertThat(serviceWith(registry).isCircuitOpen("cb-smartup.online")).isTrue();
    }

    @Test
    void halfOpenCircuitReadsAsNotOpenBecauseProbesAreAllowed() {
        CircuitBreaker cb = registry.circuitBreaker("cb-smartup.online");
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();

        assertThat(serviceWith(registry).isCircuitOpen("cb-smartup.online")).isFalse();
    }

    @Test
    void forcedOpenCircuitReadsAsOpen() {
        CircuitBreaker cb = registry.circuitBreaker("cb-smartup.online");
        cb.transitionToForcedOpenState();

        assertThat(serviceWith(registry).isCircuitOpen("cb-smartup.online")).isTrue();
    }

    @Test
    void unknownCircuitReadsAsNotOpen() {
        assertThat(serviceWith(registry).isCircuitOpen("cb-never-seen")).isFalse();
    }

    @Test
    void queryingAnUnknownNameDoesNotRegisterACircuitBreaker() {
        serviceWith(registry).isCircuitOpen("cb-never-seen");

        // A read-only query must not populate the registry: names come from request hosts and a
        // phantom entry would show up in metrics as a circuit that protects nothing.
        assertThat(registry.find("cb-never-seen")).isEmpty();
    }

    @Test
    void queryReadsTheSameInstanceTheSendPathWouldUse() {
        RequestMessage request = RequestMessage.builder()
                .tenantId(100L).requestId(1L)
                .method("POST")
                .baseUrl("https://smartup.online")
                .build();
        HttpRequestService service = serviceWith(registry);

        // The send path resolves its breaker out of the registry by this exact name, so opening
        // that entry must be visible to the query — otherwise the two can disagree.
        registry.circuitBreaker(service.circuitBreakerName(request)).transitionToOpenState();

        assertThat(service.isCircuitOpen(service.circuitBreakerName(request))).isTrue();
    }
}
