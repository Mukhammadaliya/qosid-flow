package uz.greenwhite.gateway.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import uz.greenwhite.gateway.config.ProxyProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxyControllerIpTest {

    private ProxyController controllerWithTrusted(List<String> trusted) {
        ProxyProperties props = mock(ProxyProperties.class);
        when(props.getTrustedProxies()).thenReturn(trusted);
        // Only getClientIp is exercised — other deps may be null.
        return new ProxyController(null, null, null, props, null, null);
    }

    @Test
    void xffIgnoredFromUntrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("203.0.113.7", controllerWithTrusted(List.of("127.0.0.1")).getClientIp(request));
    }

    @Test
    void xffHonoredFromTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");

        // Rightmost entry is the one appended by our trusted nginx proxy.
        assertEquals("10.0.0.1", controllerWithTrusted(List.of("127.0.0.1")).getClientIp(request));
    }

    @Test
    void xffSingleEntryFromTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("1.2.3.4", controllerWithTrusted(List.of("127.0.0.1")).getClientIp(request));
    }
}
