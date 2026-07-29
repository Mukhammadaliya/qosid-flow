package uz.greenwhite.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.api.model.CallbackPayload;
import uz.greenwhite.gateway.callback.CallbackSender;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.model.RequestState;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.notification.NotificationService;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseMetadata;
import uz.greenwhite.gateway.storage.ResponseStorageService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test controller for manual testing.
 * Only available when profile is "dev".
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Profile("dev")
public class TestController {

    private final RequestProducer requestProducer;
    private final RequestStateService requestStateService;
    private final NotificationService notificationService;
    private final ResponseStorageService responseStorageService;
    private final CallbackSender callbackSender;

    /**
     * Send test request to Kafka
     *
     * Example: POST http://localhost:8090/api/test/send
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendTestRequest() {
        RequestMessage request = RequestMessage.builder()
                .tenantId(100L)
                .requestId(System.currentTimeMillis())
                .extraTenantId(1L)
                .endpointId(1L)
                .baseUrl("https://httpbin.org")
                .uri("/post")
                .method("POST")
                .headers(Map.of("X-Test-Header", "test-value"))
                .body("{\"test\": \"data\", \"timestamp\": \"" + LocalDateTime.now() + "\"}")
                .callbackProcedure("bmb_test.process_response")
                .projectCode("TEST")
                .createdAt(LocalDateTime.now())
                .build();

        requestProducer.sendOracleRequest(request);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "sent");
        response.put("compositeId", request.getCompositeId());
        response.put("message", "Oracle request sent to Kafka");

        log.info("Test request sent: {}", request.getCompositeId());
        return ResponseEntity.ok(response);
    }

    /**
     * Check request state in Redis
     *
     * Example: GET http://localhost:8090/api/test/state/100:1234567890
     */
    @GetMapping("/state/{compositeId}")
    public ResponseEntity<Object> getState(@PathVariable String compositeId) {
        return requestStateService.getState(compositeId)
                .map(state -> ResponseEntity.ok((Object) state))
                .orElse(ResponseEntity.ok(Map.of(
                        "status", "not_found",
                        "compositeId", compositeId
                )));
    }

    /**
     * Health check
     *
     * Example: GET http://localhost:8090/api/test/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "qosid-flow",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    /**
     * Send test request WITH OAuth2 to Kafka
     *
     * Example: POST http://localhost:8090/api/test/send-oauth2
     */
    @PostMapping("/send-oauth2")
    public ResponseEntity<Map<String, Object>> sendTestOAuth2Request(
            @RequestParam(defaultValue = "main-app") String provider) {

        RequestMessage request = RequestMessage.builder()
                .tenantId(100L)
                .requestId(System.currentTimeMillis())
                .extraTenantId(1L)
                .endpointId(1L)
                .baseUrl("https://httpbin.org")
                .uri("/post")
                .method("POST")
                .headers(Map.of("X-Test-Header", "oauth2-test"))
                .body("{\"test\": \"oauth2\", \"provider\": \"" + provider + "\"}")
                .oauth2Provider(provider)
                .projectCode("TEST")
                .createdAt(LocalDateTime.now())
                .build();

        requestProducer.sendOracleRequest(request);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "sent");
        response.put("compositeId", request.getCompositeId());
        response.put("oauth2Provider", provider);
        response.put("message", "OAuth2 request sent to Kafka");

        log.info("OAuth2 test request sent: {} with provider: {}", request.getCompositeId(), provider);
        return ResponseEntity.ok(response);
    }

    /**
     * Test DLQ → notification
     *
     * Example: POST http://localhost:8090/api/test/dlq
     */
    @PostMapping("/dlq")
    public ResponseEntity<Map<String, Object>> testDlqNotification() {
        DlqMessage dlqMessage = DlqMessage.builder()
                .tenantId(100L)
                .requestId(System.currentTimeMillis())
                .originalTopic("bmb.request.new")
                .failureReason("HTTP 503 Service Unavailable after 3 retries")
                .errorSource("HTTP")
                .httpStatus(503)
                .attemptCount(3)
                .url("POST https://api.example.com/v1/students")
                .body("{\"test\": \"data\"}")
                .failedAt(LocalDateTime.now())
                .build();

        requestProducer.sendToOracleDlq(dlqMessage);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "sent_to_dlq");
        response.put("compositeId", dlqMessage.getCompositeId());
        response.put("notification_enabled", notificationService.isEnabled());
        response.put("message", "DLQ message sent");
        return ResponseEntity.ok(response);
    }

    /**
     * Emulate an inbound API request by enqueuing a synthetic {@code source="API"}
     * message. Useful for exercising the ResponseConsumer's API-branch routing
     * without going through ProxyController's multipart/header plumbing.
     *
     * Example: POST http://localhost:8090/api/test/proxy
     */
    @PostMapping("/proxy")
    public ResponseEntity<Map<String, Object>> sendTestProxyRequest(
            @RequestHeader(value = "X-Callback-Url", required = false) String callbackUrl) {
        UUID uuid = UUID.randomUUID();
        long requestId = uuid.getMostSignificantBits() & Long.MAX_VALUE;

        RequestMessage request = RequestMessage.builder()
                .tenantId(0L)
                .requestId(requestId)
                .source("API")
                .baseUrl("https://httpbin.org")
                .uri("/post")
                .method("POST")
                .headers(Map.of("X-Test-Header", "api-proxy-test"))
                .body("{\"test\": \"api-proxy\", \"uuid\": \"" + uuid + "\"}")
                .clientIp("127.0.0.1")
                .contentType("application/json")
                .callbackUrl(callbackUrl)
                .createdAt(LocalDateTime.now())
                .build();

        requestProducer.sendApiRequest(request);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "sent");
        response.put("compositeId", request.getCompositeId());
        response.put("source", "API");
        response.put("message", "API-source test request sent to Kafka");
        log.info("Test API proxy request sent: {}", request.getCompositeId());
        return ResponseEntity.ok(response);
    }

    /**
     * Inspect RocksDB for a stored response. Returns metadata only (body is kept
     * binary and served via {@code /qosid/api/v1/requests/{id}/result}).
     *
     * Example: GET http://localhost:8090/api/test/rocksdb/0:123456789
     */
    @GetMapping("/rocksdb/{id}")
    public ResponseEntity<Object> getRocksdbMetadata(@PathVariable String id) {
        ResponseMetadata meta = responseStorageService.getResponseMetadata(id);
        if (meta == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "not_found",
                    "compositeId", id
            ));
        }
        return ResponseEntity.ok(meta);
    }

    /**
     * Send a test callback through the retrying CallbackSender. Handy for
     * verifying that the callback URL is reachable from this environment.
     *
     * Example: POST http://localhost:8090/api/test/callback?url=http://localhost:9000/hook
     */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> sendTestCallback(@RequestParam String url) {
        CallbackPayload payload = CallbackPayload.builder()
                .requestId("test:" + UUID.randomUUID())
                .status("COMPLETED")
                .httpStatus(200)
                .contentType("application/json")
                .result(Map.of("test", "payload", "timestamp", LocalDateTime.now().toString()))
                .build();

        Map<String, Object> response = new HashMap<>();
        response.put("url", url);
        response.put("requestId", payload.getRequestId());
        try {
            callbackSender.sendCallback(url, payload);
            response.put("status", "sent");
            response.put("message", "Callback delivered (or all retries exhausted — check logs)");
        } catch (Exception e) {
            // @Recover swallows after 3 attempts, so this is mainly for synchronous
            // validation errors (malformed URL, etc.).
            response.put("status", "failed");
            response.put("error", e.getMessage());
            log.warn("Test callback failed synchronously: {}", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
}