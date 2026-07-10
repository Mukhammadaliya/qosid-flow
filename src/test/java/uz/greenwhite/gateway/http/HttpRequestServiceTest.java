package uz.greenwhite.gateway.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import uz.greenwhite.gateway.config.HttpProperties;
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
    @InjectMocks HttpRequestService service;

    @Test
    void nullMethodYieldsSystemErrorResponseInsteadOfThrowing() {
        RequestMessage request = RequestMessage.builder()
                .companyId(1L).requestId(2L)
                .baseUrl("https://example.com")
                .method(null)
                .build();

        ResponseMessage response = service.sendRequest(request).block();

        assertNotNull(response);
        assertEquals("SYSTEM", response.getErrorSource());
        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("method"));
    }
}
