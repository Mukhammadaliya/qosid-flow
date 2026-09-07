package uz.greenwhite.gateway.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import uz.greenwhite.gateway.config.HttpProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class HttpRequestServiceTest {

    @Mock WebClient webClient;
    @Mock CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock OAuth2ProviderService oAuth2ProviderService;
    @Mock HttpProperties httpProperties;
    @Mock HostConcurrencyLimiter hostLimiter;
    @Mock GatewayMetrics metrics;
    @InjectMocks HttpRequestService service;

    @Test
    void nullMethodYieldsSystemErrorResponseInsteadOfThrowing() {
        RequestMessage request = RequestMessage.builder()
                .tenantId(1L).requestId(2L)
                .baseUrl("https://example.com")
                .method(null)
                .build();

        ResponseMessage response = service.sendRequest(request).block();

        assertNotNull(response);
        assertEquals("SYSTEM", response.getErrorSource());
        assertEquals(0, response.getHttpStatus());
        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("method"));
    }

    @Test
    void nullMethodShortCircuitsBeforeAnyCollaboratorCall() {
        RequestMessage request = RequestMessage.builder()
                .tenantId(1L).requestId(2L)
                .baseUrl("https://example.com")
                .method(null)
                .build();

        service.sendRequest(request).block();

        org.mockito.Mockito.verifyNoInteractions(
                circuitBreakerRegistry, oAuth2ProviderService, webClient, hostLimiter, metrics);
    }

    // buildFullUrl: bare query params must be joined with '?' (or '&' when the URI
    // already carries one) — without the separator /queue GETs forwarded /pathx=1.
    private RequestMessage req(String uri, String params) {
        return RequestMessage.builder()
                .tenantId(1L).requestId(2L)
                .baseUrl("https://example.com")
                .uri(uri)
                .params(params)
                .build();
    }

    @Test
    void bareParamsJoinedWithQuestionMark() {
        assertEquals("https://example.com/small?a=1&b=2",
                service.buildFullUrl(req("/small", "a=1&b=2")));
    }

    @Test
    void paramsWithLeadingQuestionMarkNotDoubled() {
        assertEquals("https://example.com/small?a=1",
                service.buildFullUrl(req("/small", "?a=1")));
    }

    @Test
    void uriAlreadyHasQueryParamsJoinedWithAmpersand() {
        assertEquals("https://example.com/small?x=0&a=1",
                service.buildFullUrl(req("/small?x=0", "a=1")));
    }

    @Test
    void noParamsLeavesUrlUntouched() {
        assertEquals("https://example.com/small",
                service.buildFullUrl(req("/small", null)));
    }

    // params may carry a path segment before its query string -- the documented BMB contract
    // (bmb_requests.params: "URL parameters including path and query string, ex: /John?year=2025").
    // Inserting a separator there pushes the segment into the query and the request hits the
    // service root, which an OData service answers with 200 and its service document.
    @Test
    void pathSegmentInParamsStaysOnThePath() {
        assertEquals("https://example.com/Catalog_X?$select=Ref_Key,Code",
                service.buildFullUrl(req("/", "Catalog_X?$select=Ref_Key,Code")));
    }

    @Test
    void documentedParamsExampleIsJoinedVerbatim() {
        assertEquals("https://example.com/John?year=2025",
                service.buildFullUrl(req("", "/John?year=2025")));
    }

    @Test
    void paramsContinuingThePathGetNoSeparator() {
        assertEquals("https://example.com/api/items/42",
                service.buildFullUrl(req("/api/items", "/42")));
    }

    @Test
    void perRequestKeyInPathSegmentSurvives() {
        assertEquals("https://example.com/Document_D(guid'abc')?$expand=S",
                service.buildFullUrl(req("/", "Document_D(guid'abc')?$expand=S")));
    }
}
