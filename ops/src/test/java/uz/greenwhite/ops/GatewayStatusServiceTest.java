package uz.greenwhite.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses a real local {@link HttpServer} stub instead of MockRestServiceServer:
 * the service constructor installs its own SimpleClientHttpRequestFactory (2s timeouts),
 * which would replace MockRestServiceServer's mock request factory binding.
 * A real stub exercises the actual HTTP path including that factory.
 */
class GatewayStatusServiceTest {

    private HttpServer server;
    private String healthUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        healthUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
        server.start();
    }

    @AfterEach
    void stop() {
        try {
            server.stop(0);
        } catch (Exception ignored) {
            // already stopped by the test itself
        }
    }

    private void healthRespondsWith(int status) {
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }

    @Test
    void upWhenHealthReturns200() {
        healthRespondsWith(200);
        GatewayStatusService svc = new GatewayStatusService(RestClient.builder(), healthUrl);
        assertTrue(svc.isUp());
    }

    @Test
    void downWhenHealthErrors() {
        healthRespondsWith(500);
        GatewayStatusService svc = new GatewayStatusService(RestClient.builder(), healthUrl);
        assertFalse(svc.isUp()); // 5xx → down, never throws
    }

    @Test
    void downWhenGatewayUnreachable() {
        server.stop(0); // nothing listening on the port anymore → connection refused
        GatewayStatusService svc = new GatewayStatusService(RestClient.builder(), healthUrl);
        assertFalse(svc.isUp()); // connect error → down, never throws
    }
}
