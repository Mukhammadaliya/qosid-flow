package uz.greenwhite.gateway.api;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import uz.greenwhite.gateway.auth.AuthResult;
import uz.greenwhite.gateway.auth.AuthorizationProvider;
import uz.greenwhite.gateway.config.ApiProperties;
import uz.greenwhite.gateway.config.ProxyProperties;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST ingress must stamp {@code createdAt} on every enqueued message.
 *
 * <p>{@code *RequestConsumer.isMessageExpired} returns false when the field is null,
 * so an unstamped message permanently disables the {@code retry.max-message-age-ms}
 * net that SAFETY-RULES 23 and 24 name as the only backstop for the CB-blocked and
 * OAuth2-evict re-queue paths.
 */
class ProxyControllerCreatedAtTest {

    @Test
    @SuppressWarnings("unchecked")
    void restIngressStampsCreatedAt() throws Exception {
        AuthorizationProvider auth = mock(AuthorizationProvider.class);
        AuthResult allowed = mock(AuthResult.class);
        when(allowed.isAllowed()).thenReturn(true);
        when(allowed.getTenantId()).thenReturn(7L);
        when(auth.authorize(any())).thenReturn(allowed);

        RequestProducer producer = mock(RequestProducer.class);
        when(producer.sendApiRequest(any())).thenReturn(CompletableFuture.completedFuture(null));

        ProxyProperties proxyProps = mock(ProxyProperties.class);
        when(proxyProps.getTrustedProxies()).thenReturn(List.of("127.0.0.1"));
        when(proxyProps.getPrefix()).thenReturn("/qosid/api/v1/queue");
        when(proxyProps.getContextPath()).thenReturn("/qosid");

        ApiProperties apiProps = mock(ApiProperties.class);
        when(apiProps.getBaseUrl()).thenReturn("http://upstream.test");

        SettingsService settings = mock(SettingsService.class);
        when(settings.get(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1, String.class));

        ProxyController controller = new ProxyController(
                auth, producer, null, proxyProps, apiProps, null, settings);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/qosid/api/v1/queue/ping");
        request.setRemoteAddr("127.0.0.1");

        controller.handleRequest(request);

        ArgumentCaptor<RequestMessage> captor = ArgumentCaptor.forClass(RequestMessage.class);
        verify(producer).sendApiRequest(captor.capture());
        RequestMessage sent = captor.getValue();

        assertNotNull(sent.getCreatedAt(),
                "REST ingress must stamp createdAt — the expiry net reads it");
        assertTrue(sent.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(1)));
    }
}
