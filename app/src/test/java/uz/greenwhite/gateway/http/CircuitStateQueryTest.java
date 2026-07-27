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

    /**
     * A second host, because one asserted name cannot tell a derivation apart from a constant.
     * This one is the example in {@code extractDomainForCB}'s own javadoc and it also pins the
     * port being stripped — {@code :8080} must not reach the name, or the same service reached on
     * two ports would be protected by two independent circuits.
     */
    @Test
    void circuitBreakerNameDropsSchemeAndPort() {
        HttpRequestService service = serviceWith(registry);
        RequestMessage request = RequestMessage.builder()
                .tenantId(100L).requestId(2L)
                .method("GET")
                .baseUrl("http://api.example.com:8080")
                .build();

        assertThat(service.circuitBreakerName(request)).isEqualTo("cb-api.example.com");
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

    /**
     * The registry side uses the literal name on purpose. Deriving it from
     * {@code circuitBreakerName} on both sides would make this pass for any naming rule at all,
     * including a constant — it would prove the two calls agree with each other rather than that
     * either agrees with the send path.
     */
    @Test
    void queryReadsTheEntryTheSendPathWouldOpen() {
        RequestMessage request = RequestMessage.builder()
                .tenantId(100L).requestId(1L)
                .method("POST")
                .baseUrl("https://smartup.online")
                .build();
        HttpRequestService service = serviceWith(registry);

        // This is literally what the send path does: registry.circuitBreaker(extractDomainForCB(baseUrl)).
        registry.circuitBreaker("cb-smartup.online").transitionToOpenState();

        assertThat(service.isCircuitOpen(service.circuitBreakerName(request))).isTrue();
    }
}
