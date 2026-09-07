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

    // buildFullUrl appends params verbatim: params carries its own separator (the documented
    // BMB contract, bmb_requests.params = "URL parameters including path and query string,
    // ex: /John?year=2025"). The /queue proxy prepends the '?' itself -- see
    // ProxyController.querySeparated -- because a path segment and a valueless query flag are
    // indistinguishable here.
    private RequestMessage req(String uri, String params) {
        return RequestMessage.builder()
                .tenantId(1L).requestId(2L)
                .baseUrl("https://example.com")
                .uri(uri)
                .params(params)
                .build();
    }

    private void url(String expected, String uri, String params) {
        assertEquals(expected, service.buildFullUrl(req(uri, params)));
    }

    // --- /queue proxy: the separator arrives with the params ---------------------------------

    @Test
    void proxyQueryCarriesItsOwnSeparator() {
        url("https://example.com/small?a=1&b=2", "/small", "?a=1&b=2");
    }

    @Test
    void proxyValuelessFlagQueryIsNotMistakenForAPath() {
        url("https://example.com/small?debug", "/small", "?debug");
    }

    @Test
    void noParamsLeavesUrlUntouched() {
        url("https://example.com/small", "/small", null);
    }

    @Test
    void emptyParamsLeavesUrlUntouched() {
        url("https://example.com/small", "/small", "");
    }

    @Test
    void nullUriAndParamsYieldTheBaseUrl() {
        url("https://example.com", null, null);
    }

    // --- Oracle senders: every params form sbe_dalion builds ----------------------------------

    @Test
    void createPostAddressesTheCollection() {                       // Send_Order_Export
        url("https://example.com/Document_Foo", "/", "Document_Foo");
    }

    @Test
    void patchAddressesOneDocumentByKey() {                         // Send_Order_Update/Status/Cancel
        url("https://example.com/Document_Foo(guid'abc')", "/", "Document_Foo(guid'abc')");
    }

    @Test
    void readWithSelectAndFilter() {                                // Send_Product_Sync, partners, warehouses
        url("https://example.com/Catalog_Foo?$select=Ref_Key,Code&$filter=IsFolder eq false",
            "/", "Catalog_Foo?$select=Ref_Key,Code&$filter=IsFolder eq false");
    }

    @Test
    void readWithExpand() {                                         // Send_Order_Sync
        url("https://example.com/Document_Foo?$expand=Bar&$filter=Date ge datetime'2026-09-05'",
            "/", "Document_Foo?$expand=Bar&$filter=Date ge datetime'2026-09-05'");
    }

    @Test
    void virtualTableUnderAnAccumulationRegister() {                // Send_Balance_Sync
        url("https://example.com/AccumulationRegister_Foo/Balance?$filter=Bar eq guid'abc'",
            "/", "AccumulationRegister_Foo/Balance?$filter=Bar eq guid'abc'");
    }

    @Test
    void sliceLastUnderAnInformationRegister() {                    // Send_Price_Sync
        url("https://example.com/InformationRegister_Foo_RecordType/SliceLast?$filter=Bar/Code eq '1'",
            "/", "InformationRegister_Foo_RecordType/SliceLast?$filter=Bar/Code eq '1'");
    }

    // --- the contract's own example ----------------------------------------------------------

    @Test
    void documentedParamsExampleIsJoinedVerbatim() {
        url("https://example.com/John?year=2025", "", "/John?year=2025");
    }

    @Test
    void paramsContinuingThePathGetNoSeparator() {
        url("https://example.com/api/items/42", "/api/items", "/42");
    }

    // The REST proxy hands over the client's raw query string as params, and a query VALUE may
    // legitimately contain a bare '?' (RFC 3986 allows it in the query part). That must still be
    // recognised as a bare query and get its separator — only a '?' that comes BEFORE the first
    // '=' marks a "path?query" params.
    @Test
    void bareQueryWithQuestionMarkInsideAValueStillGetsSeparator() {
        assertEquals("https://example.com/search?filter=a?b&x=1",
                service.buildFullUrl(req("/search", "filter=a?b&x=1")));
    }

    @Test
    void bareQueryWithUrlValueContainingQueryStillGetsSeparator() {
        assertEquals("https://example.com/go?redirect=https://x/y?z=1",
                service.buildFullUrl(req("/go", "redirect=https://x/y?z=1")));
    }

    @Test
    void pathThenQueryWithoutAnyEqualsIsJoinedVerbatim() {
        assertEquals("https://example.com/Catalog_X?$top",
                service.buildFullUrl(req("/", "Catalog_X?$top")));
    }
}
