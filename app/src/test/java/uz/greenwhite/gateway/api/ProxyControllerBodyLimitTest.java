package uz.greenwhite.gateway.api;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import uz.greenwhite.gateway.auth.AuthResult;
import uz.greenwhite.gateway.auth.AuthorizationProvider;
import uz.greenwhite.gateway.config.ApiProperties;
import uz.greenwhite.gateway.config.ProxyProperties;
import uz.greenwhite.gateway.exception.PayloadTooLargeException;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The non-multipart body used to be read with an unbounded
 * {@code request.getInputStream().readAllBytes()} — a single request could buffer
 * arbitrarily many bytes on a Tomcat worker thread (memory-exhaustion vector).
 *
 * <p>The limit is {@code gateway.proxy.max-body-size} and applies ONLY to this raw
 * branch; the {@code BiruniUpload} multipart branch stays governed by
 * {@code spring.servlet.multipart.*} so real S3 uploads are untouched.</p>
 */
class ProxyControllerBodyLimitTest {

    private RequestProducer producer;

    private ProxyController controllerWithLimit(long maxBodySize) {
        AuthorizationProvider auth = mock(AuthorizationProvider.class);
        AuthResult allowed = mock(AuthResult.class);
        when(allowed.isAllowed()).thenReturn(true);
        when(allowed.getTenantId()).thenReturn(7L);
        when(auth.authorize(any())).thenReturn(allowed);

        producer = mock(RequestProducer.class);
        when(producer.sendApiRequest(any())).thenReturn(CompletableFuture.completedFuture(null));

        ProxyProperties proxyProps = mock(ProxyProperties.class);
        when(proxyProps.getTrustedProxies()).thenReturn(List.of("127.0.0.1"));
        when(proxyProps.getPrefix()).thenReturn("/qosid/api/v1/queue");
        when(proxyProps.getContextPath()).thenReturn("/qosid");
        when(proxyProps.getMaxBodySize()).thenReturn(maxBodySize);

        ApiProperties apiProps = mock(ApiProperties.class);
        when(apiProps.getBaseUrl()).thenReturn("http://upstream.test");

        SettingsService settings = mock(SettingsService.class);
        when(settings.get(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1, String.class));

        return new ProxyController(auth, producer, null, proxyProps, apiProps, null, settings);
    }

    private static MockHttpServletRequest postWithBody(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/qosid/api/v1/queue/x");
        request.setRemoteAddr("127.0.0.1");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    @Test
    void bodyAtTheLimitIsAccepted() throws Exception {
        ProxyController controller = controllerWithLimit(64);
        byte[] body = "a".repeat(64).getBytes();

        controller.handleRequest(postWithBody(body));

        ArgumentCaptor<RequestMessage> captor = ArgumentCaptor.forClass(RequestMessage.class);
        verify(producer).sendApiRequest(captor.capture());
        assertEquals(64, captor.getValue().getBody().length(),
                "A body exactly at the limit must pass through unchanged");
    }

    @Test
    void bodyOverTheLimitIsRejectedAndNeverEnqueued() {
        ProxyController controller = controllerWithLimit(64);
        byte[] body = "a".repeat(65).getBytes();

        PayloadTooLargeException e = assertThrows(PayloadTooLargeException.class,
                () -> controller.handleRequest(postWithBody(body)));

        assertTrue(e.getMessage().contains("64"), "Message should name the configured limit");
        verify(producer, never()).sendApiRequest(any());
    }

    /**
     * Content-Length is client-supplied, so an honest oversize declaration must be refused
     * before the body is buffered — but a LYING (understated) Content-Length must not get
     * through either, which is what the streaming counter below covers.
     */
    @Test
    void understatedContentLengthStillHitsTheStreamingLimit() {
        ProxyController controller = controllerWithLimit(64);
        MockHttpServletRequest request = postWithBody("a".repeat(200).getBytes());
        request.addHeader("Content-Length", "10"); // lie

        assertThrows(PayloadTooLargeException.class, () -> controller.handleRequest(request));
        verify(producer, never()).sendApiRequest(any());
    }

    /** Escape hatch for operators who must accept anything the pipeline can carry. */
    @Test
    void zeroOrNegativeLimitDisablesTheCheck() throws Exception {
        ProxyController controller = controllerWithLimit(0);

        controller.handleRequest(postWithBody("a".repeat(4096).getBytes()));

        verify(producer).sendApiRequest(any());
    }

    /**
     * The default must be no tighter than the Kafka message ceiling
     * ({@code max.request.size} / {@code fetch.max.bytes} = 20 MiB): a body above that
     * already fails at the producer today, so a 20 MiB gate cannot reject a request that
     * currently succeeds — while still bounding the memory a single request can pin.
     */
    @Test
    void defaultLimitMatchesTheKafkaMessageCeiling() {
        assertEquals(20 * 1024 * 1024, new ProxyProperties().getMaxBodySize());
    }
}
