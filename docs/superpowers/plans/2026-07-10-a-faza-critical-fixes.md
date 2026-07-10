# A-faza: Kritik Tuzatishlar — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 10 critical security/reliability findings from the 2026-07-10 full-code analysis without changing pipeline behavior otherwise.

**Architecture:** Surgical fixes to existing classes plus two new small classes (`CallbackUrlValidator`, `StorageWriteException`). Every fix keeps the SAFETY-RULES invariants (MANUAL ack, dual idempotency, Integer.MAX_VALUE net, DLQ fallback). First tests in the project are introduced here.

**Tech Stack:** Java 21, Spring Boot 3.3.0, JUnit 5 + Mockito (from `spring-boot-starter-test`, already in pom.xml), Maven.

## Global Constraints

- Branch: work directly on `debug`. Never push `master`.
- Base package `uz.greenwhite.gateway`. Follow existing Lombok/logging idioms (`@Slf4j`, `@RequiredArgsConstructor` where present).
- NEVER violate `ai-context/SAFETY-RULES.md` — read it before Tasks 5–8.
- All Kafka acks stay MANUAL; every failure path still ends in success, re-queue, nack, or DLQ — no silent drops.
- Build check after each task: `mvn -q compile` must pass; test check: `mvn -q test` must pass.
- Prod behavior guard: defaults must keep current prod working via its existing `.env` (`TELEGRAM_ENABLED` is NOT set in prod `.env` — its default must stay `true`).

---

### Task 1: Test harness bootstrap

**Files:**
- Create: `src/test/java/uz/greenwhite/gateway/util/AuthUtilTest.java`

**Interfaces:**
- Consumes: `uz.greenwhite.gateway.util.AuthUtil.generateBasicAuth(String, String)` (existing static method returning `"Basic <base64(user:pass)>"`, used by `BiruniClient`).
- Produces: a green `mvn test` baseline all later tasks build on.

- [ ] **Step 1: Write the test**

```java
package uz.greenwhite.gateway.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthUtilTest {

    @Test
    void generatesRfc7617BasicAuthHeader() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("admin:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, AuthUtil.generateBasicAuth("admin", "secret"));
    }
}
```

Note: open `src/main/java/uz/greenwhite/gateway/util/AuthUtil.java` first. If the method name/signature differs (e.g. takes a single joined string), adapt the test to the real signature — the goal is a trivially-true test of existing behavior.

- [ ] **Step 2: Run the test suite**

Run: `mvn test -q`
Expected: `Tests run: 1, Failures: 0, Errors: 0` (BUILD SUCCESS). If Surefire reports "No tests to run", check the file is under `src/test/java`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/uz/greenwhite/gateway/util/AuthUtilTest.java
git commit -m "test: bootstrap JUnit test harness with first unit test"
```

---

### Task 2: Remove hardcoded secrets + Telegram hardening

**Files:**
- Modify: `src/main/resources/application.yml` (lines 104-106, 214-218)
- Modify: `src/main/java/uz/greenwhite/gateway/notification/TelegramNotificationService.java`
- Test: `src/test/java/uz/greenwhite/gateway/notification/TelegramNotificationServiceTest.java`

**Interfaces:**
- Consumes: `TelegramProperties` (getters: `getBotToken():String`, `getChatId():Long`, `getMessageThreadId():Integer`, `getConnectTimeoutMs():int`, `getReadTimeoutMs():int`), `DlqMessage.builder()` (fields: companyId:Long, requestId:Long, failureReason, errorSource, httpStatus:int, attemptCount:int).
- Produces: `TelegramNotificationService.isEnabled()` now returns `false` when the bot token is blank (used by DLQ consumers unchanged).

Context: the old bot token was leaked from this exact file and the bot was hijacked + frozen by Telegram (2026-07-10 incident). Empty defaults are the fix; prod supplies real values via `/opt/services/qosid-flow/.env`.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.notification;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.TelegramProperties;
import uz.greenwhite.gateway.model.kafka.DlqMessage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TelegramNotificationServiceTest {

    @Test
    void blankTokenDisablesServiceInsteadOfSendingDoomedRequests() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("");   // if TelegramProperties is not @Data with setters, construct however the class allows
        props.setChatId(0L);

        TelegramNotificationService service = new TelegramNotificationService(props);

        assertFalse(service.isEnabled());
        assertDoesNotThrow(() -> service.sendDlqAlert(
                DlqMessage.builder().companyId(1L).requestId(2L).failureReason("x").build()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=TelegramNotificationServiceTest`
Expected: FAIL — `isEnabled()` currently hardcodes `return true`.

- [ ] **Step 3: Implement**

In `TelegramNotificationService.java`:

Replace the constructor and `isEnabled` (current lines 27-31, 45-48), and add timeouts (the `RestTemplate` currently has none — a hung Telegram call blocks the DLQ consumer thread):

```java
    private final boolean enabled;

    public TelegramNotificationService(TelegramProperties properties) {
        this.properties = properties;
        this.enabled = properties.getBotToken() != null && !properties.getBotToken().isBlank();

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);

        if (enabled) {
            log.info("Telegram notification ENABLED: chatId={}", properties.getChatId());
        } else {
            log.warn("Telegram notification DISABLED: bot token is blank (set TELEGRAM_BOT_TOKEN)");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
```

And guard `sendDlqAlert` (insert as first lines of the method):

```java
        if (!enabled) {
            log.warn("Telegram disabled — DLQ alert not sent: {}", message.getCompositeId());
            return;
        }
```

Note: check `TelegramProperties` for the exact timeout getter names (`CONFIGURATION.md` documents `connect-timeout-ms`/`read-timeout-ms` with defaults 5000). If the fields don't exist in the class, add them with default `5000` following the class's existing Lombok style.

- [ ] **Step 4: Edit application.yml**

Replace lines 215-218:

```yaml
  telegram:
    enabled: ${TELEGRAM_ENABLED:true}
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    chat-id: ${TELEGRAM_CHAT_ID:0}
    message-thread-id: ${TELEGRAM_MESSAGE_THREAD_ID:}
```

Replace lines 105-106 (Biruni default credentials):

```yaml
    username: ${SOURCE_USERNAME:}
    password: ${SOURCE_PASSWORD:}
```

Keep `enabled` default `true` — prod `.env` does not set `TELEGRAM_ENABLED`; the blank-token guard handles dev.

- [ ] **Step 5: Run tests**

Run: `mvn test -q`
Expected: PASS (all tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(security): remove hardcoded Telegram/Biruni secrets, disable Telegram on blank token, add HTTP timeouts"
```

---

### Task 3: Surface RocksDB write failures (no more phantom results)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/storage/StorageWriteException.java`
- Modify: `src/main/java/uz/greenwhite/gateway/storage/RocksDBClient.java:32-39,53-63`
- Modify: `src/main/java/uz/greenwhite/gateway/storage/ResponseStorageService.java:30-42`
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiResponseConsumer.java:49-54`
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleResponseConsumer.java:57-62`
- Test: `src/test/java/uz/greenwhite/gateway/storage/ResponseStorageServiceTest.java`

**Interfaces:**
- Produces: `StorageWriteException extends RuntimeException`; `ResponseStorageService.saveResponse(...)` now throws it on write failure. Consumers catch it, keep `hasResult=false`, and continue processing (callback / Oracle save still run).
- Consumes: `GatewayMetrics.getRocksdbSaveSuccess():Counter` and `getRocksdbSaveError():Counter` (existing fields in `GatewayMetrics.java:75-76`, exposed via Lombok `@Getter`).

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.storage;

import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.metrics.GatewayMetrics;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponseStorageServiceTest {

    @Mock RocksDBClient rocksDBClient;
    @Mock RocksDBProperties rocksDBProperties;
    @Mock GatewayMetrics metrics;
    @Mock Counter successCounter;
    @Mock Counter errorCounter;

    @Test
    void writeFailurePropagatesAndCountsError() {
        when(metrics.getRocksdbSaveError()).thenReturn(errorCounter);
        doThrow(new StorageWriteException("disk full", null))
                .when(rocksDBClient).putResponse(any(), any());

        ResponseStorageService service =
                new ResponseStorageService(rocksDBClient, rocksDBProperties, metrics);

        assertThrows(StorageWriteException.class,
                () -> service.saveResponse("1:2", new byte[]{1}, 200, "application/json"));
        verify(errorCounter).increment();
    }

    @Test
    void successfulWriteCountsSuccess() {
        when(metrics.getRocksdbSaveSuccess()).thenReturn(successCounter);

        ResponseStorageService service =
                new ResponseStorageService(rocksDBClient, rocksDBProperties, metrics);
        service.saveResponse("1:2", new byte[]{1}, 200, "application/json");

        verify(successCounter).increment();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=ResponseStorageServiceTest`
Expected: FAIL — compile error (`StorageWriteException` does not exist; constructor arity mismatch).

- [ ] **Step 3: Implement**

New `StorageWriteException.java`:

```java
package uz.greenwhite.gateway.storage;

/** Thrown when a RocksDB write fails — callers must NOT report the response as stored. */
public class StorageWriteException extends RuntimeException {
    public StorageWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`RocksDBClient.putResponse` (replace body of catch):

```java
    public void putResponse(String compositeId, byte[] body) {
        byte[] key = bodyKey(compositeId);
        try {
            rocksDB.put(key, body);
        } catch (RocksDBException e) {
            log.error("RocksDB putResponse failed for {}: {}", compositeId, e.getMessage(), e);
            throw new StorageWriteException("RocksDB body write failed for " + compositeId, e);
        }
    }
```

`RocksDBClient.putResponseMetadata` — same pattern for both catch blocks:

```java
    public void putResponseMetadata(String compositeId, ResponseMetadata meta) {
        byte[] key = metaKey(compositeId);
        try {
            byte[] value = objectMapper.writeValueAsBytes(meta);
            rocksDB.put(key, value);
        } catch (RocksDBException e) {
            log.error("RocksDB putResponseMetadata failed for {}: {}", compositeId, e.getMessage(), e);
            throw new StorageWriteException("RocksDB metadata write failed for " + compositeId, e);
        } catch (Exception e) {
            log.error("Failed to serialize ResponseMetadata for {}: {}", compositeId, e.getMessage(), e);
            throw new StorageWriteException("ResponseMetadata serialization failed for " + compositeId, e);
        }
    }
```

`ResponseStorageService` — add `private final GatewayMetrics metrics;` field (constructor comes from `@RequiredArgsConstructor` — keep field order `rocksDBClient, rocksDBProperties, metrics` to match the test) and rewrite `saveResponse`:

```java
    public void saveResponse(String compositeId, byte[] body, int httpStatus, String contentType) {
        ResponseMetadata meta = ResponseMetadata.builder()
                .httpStatus(httpStatus)
                .contentType(contentType)
                .contentLength(body != null ? body.length : 0L)
                .savedAt(System.currentTimeMillis())
                .build();

        try {
            rocksDBClient.putResponse(compositeId, body);
            rocksDBClient.putResponseMetadata(compositeId, meta);
        } catch (StorageWriteException e) {
            metrics.getRocksdbSaveError().increment();
            throw e;
        }

        metrics.getRocksdbSaveSuccess().increment();
        log.debug("Response saved to RocksDB: {}", compositeId);
    }
```

Add import `uz.greenwhite.gateway.metrics.GatewayMetrics`.

- [ ] **Step 4: Update both response consumers**

In `ApiResponseConsumer.consumeResponse` replace lines 49-54 with:

```java
            if (message.getBody() != null) {
                try {
                    responseStorageService.saveResponse(
                            message.getCompositeId(), message.getBody(),
                            message.getHttpStatus(), message.getContentType());
                    requestStateService.updateHasResult(message.getCompositeId(), true);
                } catch (StorageWriteException e) {
                    log.error("[API] RocksDB save failed for {} — /result will 404, continuing pipeline: {}",
                            key, e.getMessage());
                }
            }
```

In `OracleResponseConsumer.consumeResponse` replace lines 57-62 with the identical block but the `[ORACLE]` log tag:

```java
            if (message.getBody() != null) {
                try {
                    responseStorageService.saveResponse(
                            message.getCompositeId(), message.getBody(),
                            message.getHttpStatus(), message.getContentType());
                    requestStateService.updateHasResult(message.getCompositeId(), true);
                } catch (StorageWriteException e) {
                    log.error("[ORACLE] RocksDB save failed for {} — /result will 404, continuing pipeline: {}",
                            key, e.getMessage());
                }
            }
```

Add `import uz.greenwhite.gateway.storage.StorageWriteException;` to both.

- [ ] **Step 5: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(storage): surface RocksDB write failures instead of phantom COMPLETED results"
```

---

### Task 4: Null-safe OAuth2 token parsing in BiruniOAuth2Client

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/oauth2/client/BiruniOAuth2Client.java:72-86`
- Test: `src/test/java/uz/greenwhite/gateway/oauth2/client/BiruniOAuth2ClientTest.java`

**Interfaces:**
- Consumes: `Token` record — constructed as `new Token(accessToken, expiresInMs, tokenType, refreshToken)` (same 4-arg call as current code line 76-81; check the record before writing the test and use its actual accessor names, e.g. `accessToken()`, `refreshToken()`).
- Produces: `parseToken(String)` becomes package-private (was private) for direct testing; throws `IllegalStateException` with a clear message on missing mandatory fields instead of NPE.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.oauth2.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.oauth2.model.Token;

import static org.junit.jupiter.api.Assertions.*;

class BiruniOAuth2ClientTest {

    private final BiruniOAuth2Client client = new BiruniOAuth2Client(null, new ObjectMapper());

    @Test
    void missingRefreshTokenIsTolerated() {
        Token token = client.parseToken(
                "{\"access_token\":\"abc\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        assertEquals("abc", token.accessToken());
        assertNull(token.refreshToken());
        assertEquals(3_600_000L, token.expiresIn());
    }

    @Test
    void missingAccessTokenFailsWithClearMessage() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> client.parseToken("{\"error\":\"invalid_client\"}"));
        assertTrue(e.getMessage().contains("OAuth2"));
        assertFalse(e.getCause() instanceof NullPointerException,
                "must be a validation failure, not an NPE");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=BiruniOAuth2ClientTest`
Expected: FAIL — `parseToken` is private (compile error), and current code NPEs on missing fields.

- [ ] **Step 3: Implement**

Replace `parseToken` (drop `private`, add null checks — mirrors `StandardOAuth2Client`'s guarded style):

```java
    Token parseToken(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);

            JsonNode accessToken = json.get("access_token");
            JsonNode expiresIn = json.get("expires_in");
            if (accessToken == null || accessToken.isNull() || expiresIn == null || expiresIn.isNull()) {
                throw new IllegalStateException(
                        "OAuth2 response missing access_token/expires_in. Keys present: "
                                + String.join(",", iteratorToList(json.fieldNames())));
            }

            String tokenType = json.hasNonNull("token_type") ? json.get("token_type").asText() : "Bearer";
            String refreshToken = json.hasNonNull("refresh_token") ? json.get("refresh_token").asText() : null;

            return new Token(
                    accessToken.asText(),
                    expiresIn.asLong() * 1000,
                    tokenType,
                    refreshToken
            );
        } catch (Exception e) {
            log.error("Failed to parse OAuth2 token response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse OAuth2 token: " + e.getMessage(), e);
        }
    }

    private static java.util.List<String> iteratorToList(java.util.Iterator<String> it) {
        java.util.List<String> list = new java.util.ArrayList<>();
        it.forEachRemaining(list::add);
        return list;
    }
```

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(oauth2): null-safe Biruni token parsing, tolerate missing refresh_token"
```

---

### Task 5: Validate HTTP method before the reactive chain (no silent message loss)

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java:101-131`
- Test: `src/test/java/uz/greenwhite/gateway/http/HttpRequestServiceTest.java`

**Interfaces:**
- Consumes: `RequestMessage.builder()` (fields companyId:Long, requestId:Long, method:String, baseUrl:String).
- Produces: `sendRequest` returns `Mono.just(<ResponseMessage errorSource="SYSTEM", httpStatus=0>)` for null/blank method instead of throwing synchronously. Consumers already route `errorSource=SYSTEM` + non-retryable status 0 to permanent failure + DLQ — no consumer change needed.

Why: `request.getMethod().toUpperCase()` at line 129 NPEs synchronously inside `processRequest`, which today only releases the lock and acks — the message vanishes with no DLQ record.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=HttpRequestServiceTest`
Expected: FAIL with `NullPointerException` (thrown synchronously, not captured in the Mono).

- [ ] **Step 3: Implement**

In `sendRequest`, insert immediately after `String compositeId = request.getCompositeId();` (line 102):

```java
        // 0. Validate method BEFORE anything throws synchronously — a sync throw
        //    escapes the Mono and the consumer would ack without DLQ (silent loss).
        if (request.getMethod() == null || request.getMethod().isBlank()) {
            log.error("Invalid HTTP method (null/blank) for {} — permanent failure", compositeId);
            return Mono.just(buildSystemErrorResponse(request, "HTTP method is missing on the request"));
        }
```

Change line 129 from `HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase());` to:

```java
        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase(Locale.ROOT));
```

Add the builder method next to `buildOAuth2ErrorResponse` (line 318):

```java
    /**
     * Build response for internal/system failures detected before the HTTP call (status 0).
     * errorSource=SYSTEM + non-retryable status routes this to permanent failure + DLQ.
     */
    private ResponseMessage buildSystemErrorResponse(RequestMessage request, String errorMessage) {
        return ResponseMessage.builder()
                .companyId(request.getCompanyId())
                .requestId(request.getRequestId())
                .httpStatus(0)
                .errorMessage(errorMessage)
                .errorSource("SYSTEM")
                .processedAt(LocalDateTime.now())
                .callbackUrl(request.getCallbackUrl())
                .source(request.getSource())
                .build();
    }
```

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(http): validate method before reactive chain — sync throw no longer loses messages"
```

---

### Task 6: Redis-down at the idempotency gate → nack, not silent ack

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java:67-111`
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java:67-111` (mirror file — same lines, `[API]` tags, `sendApiRequest`)
- Test: `src/test/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumerTest.java`

**Interfaces:**
- Consumes: `Acknowledgment.nack(Duration)` (Spring Kafka, valid with MANUAL ack — seeks back and redelivers after the sleep), `RequestStateService.isCompleted/tryLock` (these propagate Redis exceptions — do NOT add catch inside `RequestStateService`; the `Integer.MAX_VALUE` net on attempt counters stays untouched).
- Produces: gate failures (Redis down) leave the message un-acked; Kafka redelivers after 5s. No message loss, no DLQ noise for infra blips.

SAFETY-RULES check: dual-check preserved verbatim; MANUAL ack preserved; the change only adds a distinct catch for the *gate* section.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.kafka.consumer.oracle;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
import uz.greenwhite.gateway.state.RequestStateService;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OracleRequestConsumerTest {

    @Mock HttpRequestService httpRequestService;
    @Mock RequestStateService requestStateService;
    @Mock RequestProducer requestProducer;
    @Mock RetryProperties retryProperties;
    @Mock ThreadPoolTaskExecutor httpExecutor;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) GatewayMetrics metrics;
    @Mock KafkaProperties kafkaProperties;
    @Mock OAuth2ProviderService oAuth2ProviderService;
    @Mock Acknowledgment ack;

    @Test
    void redisDownAtGateNacksInsteadOfAcking() {
        when(requestStateService.isCompleted("1:2"))
                .thenThrow(new RuntimeException("Unable to connect to Redis"));

        OracleRequestConsumer consumer = new OracleRequestConsumer(
                httpRequestService, requestStateService, requestProducer,
                retryProperties, httpExecutor, metrics, kafkaProperties, oAuth2ProviderService);

        RequestMessage message = RequestMessage.builder().companyId(1L).requestId(2L).build();
        consumer.consumeRequest(new ConsumerRecord<>("t", 0, 0L, "1:2", message), ack);

        verify(ack).nack(any(Duration.class));
        verify(ack, never()).acknowledge();
        verify(requestProducer, never()).sendOracleRequest(any());
        verify(requestProducer, never()).sendToOracleDlq(any());
    }
}
```

Note: `metrics.getConsumerReceived().increment()` runs before the gate — the deep-stub mock absorbs it.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=OracleRequestConsumerTest`
Expected: FAIL — current code acks in the generic catch (`verify(ack).nack` fails, `never()).acknowledge()` fails).

- [ ] **Step 3: Implement in OracleRequestConsumer**

Replace the whole body of `consumeRequest` (lines 67-111) with:

```java
    public void consumeRequest(ConsumerRecord<String, RequestMessage> record, Acknowledgment ack) {
        String key = record.key();
        RequestMessage message = record.value();

        log.info("[ORACLE] Received request: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        metrics.getConsumerReceived().increment();

        // Idempotency gate. Redis failure here must NOT ack — nack and let Kafka
        // redeliver once Redis is back (see SAFETY-RULES: no silent drops).
        try {
            if (requestStateService.isCompleted(key)) {
                log.warn("[ORACLE] Request already completed, skipping: {}", key);
                metrics.getConsumerSkippedDuplicate().increment();
                ack.acknowledge();
                return;
            }

            if (!requestStateService.tryLock(key)) {
                log.warn("[ORACLE] Request is being processed by another instance: {}", key);
                metrics.getConsumerLockFailed().increment();
                ack.acknowledge();
                return;
            }
        } catch (Exception e) {
            log.error("[ORACLE] State store unavailable for {} — nack, will redeliver: {}",
                    key, e.getMessage());
            ack.nack(Duration.ofSeconds(5));
            return;
        }

        try {
            CompletableFuture.runAsync(() -> processRequest(key, message, ack), httpExecutor)
                    .exceptionally(throwable -> {
                        log.error("[ORACLE] Unexpected error for {}: {}", key, throwable.getMessage(), throwable);
                        requestStateService.releaseLock(key);
                        ack.acknowledge();
                        return null;
                    });

        } catch (RejectedExecutionException e) {
            log.warn("[ORACLE] Pool exhausted, re-queuing: {}", key);
            metrics.getPoolRejection().increment();
            requestStateService.releaseLock(key);
            requestProducer.sendOracleRequest(message);
            ack.acknowledge();

        } catch (Exception e) {
            log.error("[ORACLE] Error submitting request {}: {}", key, e.getMessage(), e);
            requestStateService.releaseLock(key);
            ack.acknowledge();
        }
    }
```

(`java.time.Duration` is already imported in this file.)

- [ ] **Step 4: Mirror in ApiRequestConsumer**

Apply the exact same replacement to `ApiRequestConsumer.consumeRequest` with three differences only: log tags `[API]`, metric/producer calls `requestProducer.sendApiRequest(message)`, and the existing `[API]` log texts. The gate try/catch block is identical:

```java
        try {
            if (requestStateService.isCompleted(key)) {
                log.warn("[API] Request already completed, skipping: {}", key);
                metrics.getConsumerSkippedDuplicate().increment();
                ack.acknowledge();
                return;
            }

            if (!requestStateService.tryLock(key)) {
                log.warn("[API] Request is being processed by another instance: {}", key);
                metrics.getConsumerLockFailed().increment();
                ack.acknowledge();
                return;
            }
        } catch (Exception e) {
            log.error("[API] State store unavailable for {} — nack, will redeliver: {}",
                    key, e.getMessage());
            ack.nack(Duration.ofSeconds(5));
            return;
        }
```

Verify first that `ApiRequestConsumer.java` lines 67-111 are byte-identical to Oracle's (they are documented as mirror twins); if they have drifted, apply the same *pattern*, not a blind paste.

- [ ] **Step 5: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(consumer): nack on Redis gate failure instead of silently acking — no message loss during Redis outage"
```

---

### Task 7: Redis lock ownership (compare-and-delete release)

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/state/RequestStateService.java:163-184` (+ new fields)
- Modify: `src/main/java/uz/greenwhite/gateway/oauth2/OAuth2TokenRedisCache.java:61-88,117-141,189-197`
- Test: `src/test/java/uz/greenwhite/gateway/state/RequestStateServiceLockTest.java`

**Interfaces:**
- Consumes: `StringRedisTemplate` (bean already exists — `OAuth2TokenRedisCache` injects it), `DefaultRedisScript`.
- Produces: `RequestStateService.tryLock/releaseLock` keep their signatures (callers unchanged); release only deletes a lock this instance still owns. `OAuth2TokenRedisCache.acquireLock` returns `String` token (or null), `releaseLock(String providerName, String token)` — both private, callers inside the class updated.

Why: today `releaseLock` blindly `DEL`s. If processing outlives the TTL, another instance's fresh lock gets deleted → triple processing.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import uz.greenwhite.gateway.config.RedisProperties;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestStateServiceLockTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock ValueOperations<String, String> stringValueOps;
    @Mock RedisProperties redisProperties;

    @Test
    void releaseAfterAcquireUsesCompareAndDelete() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        when(redisProperties.getLockTtlSeconds()).thenReturn(300L);
        when(stringValueOps.setIfAbsent(eq("request:lock:1:2"), anyString(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        RequestStateService service =
                new RequestStateService(redisTemplate, stringRedisTemplate, redisProperties);

        assertTrue(service.tryLock("1:2"));
        service.releaseLock("1:2");

        // released via owner-checked script, not blind delete
        verify(stringRedisTemplate).execute(any(RedisScript.class), eq(List.of("request:lock:1:2")), anyString());
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void releaseWithoutOwnershipIsNoOp() {
        RequestStateService service =
                new RequestStateService(redisTemplate, stringRedisTemplate, redisProperties);

        service.releaseLock("9:9"); // never acquired by this instance

        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(redisTemplate, never()).delete(anyString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=RequestStateServiceLockTest`
Expected: FAIL — constructor arity (no `StringRedisTemplate` param yet) is a compile error.

- [ ] **Step 3: Implement in RequestStateService**

Add field (class uses `@RequiredArgsConstructor` — declare it after `redisTemplate` so the constructor order matches the test):

```java
    private final StringRedisTemplate stringRedisTemplate;
```

Add near the prefixes:

```java
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /** Lock tokens held by THIS instance; release is only valid for a token we own. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> ownedLockTokens =
            new java.util.concurrent.ConcurrentHashMap<>();
```

Replace `tryLock` and `releaseLock`:

```java
    /**
     * Try to acquire lock (for idempotency). The lock value is a random owner
     * token so that release can be compare-and-delete — a lock that expired and
     * was re-acquired by another instance will NOT be deleted by us.
     */
    public boolean tryLock(String compositeId) {
        String key = LOCK_PREFIX + compositeId;
        String token = java.util.UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, redisProperties.getLockTtlSeconds(), TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(acquired)) {
            ownedLockTokens.put(compositeId, token);
            log.debug("Lock acquired: {}", compositeId);
            return true;
        }
        log.debug("Lock already exists: {}", compositeId);
        return false;
    }

    /**
     * Release lock — only if this instance still owns it (compare-and-delete).
     */
    public void releaseLock(String compositeId) {
        String token = ownedLockTokens.remove(compositeId);
        if (token == null) {
            log.debug("Release skipped — lock not owned by this instance: {}", compositeId);
            return;
        }
        String key = LOCK_PREFIX + compositeId;
        try {
            Long deleted = stringRedisTemplate.execute(COMPARE_AND_DELETE, java.util.List.of(key), token);
            if (deleted == null || deleted == 0) {
                log.warn("Lock {} expired and was taken over before release — not deleted", compositeId);
            } else {
                log.debug("Lock released: {}", compositeId);
            }
        } catch (Exception e) {
            log.warn("Redis error releasing lock {}: {} (TTL will reap it)", compositeId, e.getMessage());
        }
    }
```

`isLocked` stays as-is.

- [ ] **Step 4: Implement in OAuth2TokenRedisCache**

Replace the lock helpers (lines 189-197):

```java
    /** @return owner token when acquired, null when someone else holds the lock */
    private String acquireLock(String providerName) {
        String token = java.util.UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY_PREFIX + providerName, token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    private void releaseLock(String providerName, String token) {
        if (token == null) return;
        try {
            redisTemplate.execute(COMPARE_AND_DELETE,
                    java.util.List.of(LOCK_KEY_PREFIX + providerName), token);
        } catch (Exception e) {
            log.warn("Redis error releasing OAuth2 lock {}: {} (TTL will reap it)", providerName, e.getMessage());
        }
    }
```

Add the script constant next to the other constants:

```java
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
```

Update `getToken` (lines 61-88) — replace the section from the old `if (!acquireLock(providerName))` through the end of the `finally` block with this single final version (acquire failure from a Redis outage now degrades instead of throwing):

```java
        String lockToken;
        try {
            lockToken = acquireLock(providerName);
        } catch (Exception e) {
            log.warn("Redis unavailable acquiring OAuth2 lock for {} — degrading: {}",
                    providerName, e.getMessage());
            if (cached != null) {
                return cached;                                    // stale token beats total failure
            }
            return performRefresh(providerName, properties, client, null); // direct uncached mint
        }

        if (lockToken == null) {
            log.debug("Another instance is refreshing token: {}, waiting...", providerName);
            return waitForToken(providerName, cached);
        }

        try {
            Token doubleCheck = getFromRedis(providerName);
            if (doubleCheck != null && !doubleCheck.isExpired()) {
                log.debug("OAuth2 token refreshed by another instance: {}", providerName);
                return doubleCheck;
            }

            Token newToken = performRefresh(providerName, properties, client, cached);
            saveToRedis(providerName, newToken);
            log.info("OAuth2 token saved to Redis: {}", providerName);
            return newToken;

        } catch (Exception e) {
            log.error("Failed to get/refresh OAuth2 token: {} - {}", providerName, e.getMessage());
            if (cached != null) {
                log.warn("Returning expired token as fallback: {}", providerName);
                return cached;
            }
            throw new RuntimeException("Failed to get OAuth2 token for: " + providerName, e);
        } finally {
            releaseLock(providerName, lockToken);
        }
```

Update `doAsyncRefresh` (lines 117-141) the same way:

```java
    private void doAsyncRefresh(String providerName, ProviderProperties properties,
                                OAuth2Client client, Token cached) {
        String lockToken;
        try {
            lockToken = acquireLock(providerName);
        } catch (Exception e) {
            log.debug("Async refresh skipped, Redis unavailable: {}", providerName);
            return;
        }
        if (lockToken == null) {
            log.debug("Async refresh skipped, another instance already refreshing: {}", providerName);
            return;
        }

        try {
            Token doubleCheck = getFromRedis(providerName);
            if (doubleCheck != null && !doubleCheck.isDueForRefresh()) {
                log.debug("Async refresh skipped, token already refreshed: {}", providerName);
                return;
            }

            log.info("Async OAuth2 token refresh started: {}", providerName);
            Token newToken = performRefresh(providerName, properties, client, cached);
            saveToRedis(providerName, newToken);
            log.info("Async OAuth2 token refresh completed: {}", providerName);
        } catch (Exception e) {
            log.warn("Async OAuth2 token refresh failed for {}: {} (cached token still valid until expiry)",
                    providerName, e.getMessage());
        } finally {
            releaseLock(providerName, lockToken);
        }
    }
```

(This also fixes the "Redis-down on lock path defeats the fallback" finding: `getToken` now degrades to the cached/direct-mint path instead of throwing before its try block.)

- [ ] **Step 5: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(locks): owner-token compare-and-delete release for request + OAuth2 locks; OAuth2 lock path degrades gracefully when Redis is down"
```

---

### Task 8: SSRF guard for X-Callback-Url

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/validation/CallbackUrlValidator.java`
- Modify: `src/main/java/uz/greenwhite/gateway/api/ProxyController.java` (new dependency + validation call after auth)
- Modify: `src/main/resources/application.yml` (new property under `gateway.security`)
- Test: `src/test/java/uz/greenwhite/gateway/validation/CallbackUrlValidatorTest.java`

**Interfaces:**
- Produces: `CallbackUrlValidator.validate(String url)` — throws `uz.greenwhite.gateway.exception.RequestValidationException` (existing, mapped to HTTP 400 by `GlobalExceptionHandler`) for: non-http(s) scheme, missing host, loopback/private/link-local targets, or host not in the allowlist (when the allowlist is non-empty).
- Consumes: `@Value("${gateway.security.callback-allowed-hosts:}") List<String>`.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.validation;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallbackUrlValidatorTest {

    private final CallbackUrlValidator open = new CallbackUrlValidator(List.of());

    @Test void publicHttpsUrlAllowed()   { assertDoesNotThrow(() -> open.validate("https://8.8.8.8/hook")); }
    @Test void loopbackRejected()        { assertThrows(RequestValidationException.class, () -> open.validate("http://127.0.0.1/x")); }
    @Test void localhostRejected()       { assertThrows(RequestValidationException.class, () -> open.validate("http://localhost:8080/x")); }
    @Test void privateRfc1918Rejected()  { assertThrows(RequestValidationException.class, () -> open.validate("http://10.0.0.5/x")); }
    @Test void private172Rejected()      { assertThrows(RequestValidationException.class, () -> open.validate("http://172.16.1.1/x")); }
    @Test void private192Rejected()      { assertThrows(RequestValidationException.class, () -> open.validate("http://192.168.1.10/x")); }
    @Test void cloudMetadataRejected()   { assertThrows(RequestValidationException.class, () -> open.validate("http://169.254.169.254/latest/meta-data")); }
    @Test void nonHttpSchemeRejected()   { assertThrows(RequestValidationException.class, () -> open.validate("ftp://example.com/x")); }
    @Test void garbageRejected()         { assertThrows(RequestValidationException.class, () -> open.validate("not a url")); }

    @Test
    void allowlistRestrictsToListedHosts() {
        CallbackUrlValidator strict = new CallbackUrlValidator(List.of("hooks.example.com"));
        assertDoesNotThrow(() -> strict.validate("https://hooks.example.com/cb"));
        assertThrows(RequestValidationException.class, () -> strict.validate("https://8.8.8.8/cb"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=CallbackUrlValidatorTest`
Expected: FAIL — class does not exist (compile error).

- [ ] **Step 3: Implement**

```java
package uz.greenwhite.gateway.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * SSRF guard for client-supplied callback URLs (X-Callback-Url header).
 *
 * <p>Rejects anything that is not plain http/https to a public address. When
 * {@code gateway.security.callback-allowed-hosts} is non-empty, the host must
 * additionally be on that list. Resolution happens once at accept time; the
 * later delivery by {@code CallbackSender} reuses the same URL.</p>
 */
@Slf4j
@Component
public class CallbackUrlValidator {

    private final List<String> allowedHosts;

    public CallbackUrlValidator(
            @Value("${gateway.security.callback-allowed-hosts:}") List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? List.of()
                : allowedHosts.stream().filter(h -> !h.isBlank())
                        .map(h -> h.toLowerCase(Locale.ROOT)).toList();
    }

    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new RequestValidationException("Invalid X-Callback-Url: not a URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new RequestValidationException("Invalid X-Callback-Url: only http/https allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RequestValidationException("Invalid X-Callback-Url: missing host");
        }

        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new RequestValidationException("X-Callback-Url host is not on the allowlist: " + host);
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (Exception e) {
            throw new RequestValidationException("Invalid X-Callback-Url: unresolvable host " + host);
        }

        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            log.warn("Blocked SSRF attempt: callback URL {} resolves to {}", url, address.getHostAddress());
            throw new RequestValidationException(
                    "X-Callback-Url must point to a public address (got " + host + ")");
        }
    }
}
```

Check `RequestValidationException`'s constructor first — if it has no `(String)` constructor, adapt the throw sites to its actual signature.

- [ ] **Step 4: Wire into ProxyController**

Add the dependency field (class uses `@RequiredArgsConstructor`):

```java
    private final uz.greenwhite.gateway.validation.CallbackUrlValidator callbackUrlValidator;
```

In `handleRequest`, right after the authorization block (after line 86), insert:

```java
        // 1b. SSRF guard on client-supplied callback URL — reject at accept time
        String callbackUrl = request.getHeader(CALLBACK_HEADER);
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            callbackUrlValidator.validate(callbackUrl);
        }
```

And change line 161 from `.callbackUrl(request.getHeader(CALLBACK_HEADER))` to `.callbackUrl(callbackUrl)`.

- [ ] **Step 5: Add the config key**

In `application.yml`, under `gateway.security` (after the `content-types` list, keep indentation):

```yaml
    # Callback (X-Callback-Url) host allowlist. Empty = any PUBLIC host allowed
    # (private/loopback/link-local are always blocked).
    callback-allowed-hosts: ${CALLBACK_ALLOWED_HOSTS:}
```

- [ ] **Step 6: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(security): SSRF guard for X-Callback-Url — block private targets, optional allowlist"
```

---

### Task 9: Trusted-proxy client IP + literal prefix strip

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/ProxyProperties.java` (new `trustedProxies` field)
- Modify: `src/main/java/uz/greenwhite/gateway/api/ProxyController.java:88-89,246-260`
- Modify: `src/main/resources/application.yml` (`gateway.proxy` section)
- Test: `src/test/java/uz/greenwhite/gateway/api/ProxyControllerIpTest.java`

**Interfaces:**
- Produces: `ProxyController.getClientIp(HttpServletRequest)` becomes a package-private **instance** method honoring `X-Forwarded-For` only when `request.getRemoteAddr()` is in `gateway.proxy.trusted-proxies`; `ProxyProperties.getTrustedProxies():List<String>`.

- [ ] **Step 1: Write the failing test**

```java
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

        assertEquals("1.2.3.4", controllerWithTrusted(List.of("127.0.0.1")).getClientIp(request));
    }
}
```

Constructor note: after Task 8 `ProxyController` has 6 injected fields in declaration order `authorizationProvider, requestProducer, fileService, proxyProperties, apiProperties, callbackUrlValidator` — wait, declaration order in the file is `authorizationProvider, requestProducer, fileService, proxyProperties, apiProperties` plus wherever Task 8 added `callbackUrlValidator`. Open the file, count the `private final` fields in order, and pass `null` for everything except `proxyProperties`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=ProxyControllerIpTest`
Expected: FAIL — `getClientIp` is currently `private static` (compile error), `getTrustedProxies` doesn't exist.

- [ ] **Step 3: Implement**

`ProxyProperties.java` — add alongside existing fields (match the class's Lombok style, e.g. `@Data @ConfigurationProperties(prefix = "gateway.proxy")`):

```java
    /** Peers (remote addresses) whose X-Forwarded-For / X-Real-IP headers are trusted. */
    private List<String> trustedProxies = new ArrayList<>();
```

(add `java.util.List` / `java.util.ArrayList` imports).

`ProxyController` — replace `getClientIp` (lines 246-260) with an instance method:

```java
    /**
     * Resolve client IP. Proxy headers are client-controlled, so they are only
     * honored when the direct peer is a declared trusted proxy (our nginx).
     */
    String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        List<String> trusted = proxyProperties.getTrustedProxies();
        if (trusted == null || !trusted.contains(remoteAddr)) {
            return remoteAddr;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            return comma > 0 ? forwardedFor.substring(0, comma).trim() : forwardedFor.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return remoteAddr;
    }
```

Also fix the regex-based prefix strip (line 89). Replace:

```java
        String targetUri = request.getRequestURI().replaceFirst(proxyProperties.getPrefix(), "");
```

with:

```java
        String requestUri = request.getRequestURI();
        String prefix = proxyProperties.getPrefix();
        String targetUri = requestUri.startsWith(prefix)
                ? requestUri.substring(prefix.length())
                : requestUri;
```

`application.yml` — extend the proxy block:

```yaml
  proxy:
    prefix: /qosid/api/v1/queue
    context-path: /qosid
    trusted-proxies: ${TRUSTED_PROXIES:127.0.0.1}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(security): honor X-Forwarded-For only from trusted proxies; literal (non-regex) prefix strip"
```

---

### Task 10: Generic 500s stop leaking internals

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/exception/GlobalExceptionHandler.java:66-70`
- Test: `src/test/java/uz/greenwhite/gateway/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `handleGeneric` returns a constant-shape message `"Internal server error (ref: <8-char id>)"`; the real exception stays in the log, findable by the same ref.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void genericHandlerHidesInternalDetailButGivesCorrelationRef() {
        ResponseEntity<ErrorResponse> response =
                handler.handleGeneric(new IllegalStateException("jdbc:oracle://secret-host:1521 timeout"));

        assertEquals(500, response.getStatusCode().value());
        String message = response.getBody().getMessage();
        assertFalse(message.contains("secret-host"), "internal detail must not leak");
        assertTrue(message.contains("ref:"), "must include a correlation ref");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — current handler echoes `e.getMessage()`.

- [ ] **Step 3: Implement**

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e) {
        String ref = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception [ref={}]: {}", ref, e.getMessage(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error (ref: " + ref + ")");
    }
```

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(security): generic 500 returns correlation ref instead of internal exception message"
```

---

### Task 11: Honest DLQ reason + BiruniClient connect timeout

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java:184`
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java:184` (mirror)
- Modify: `src/main/java/uz/greenwhite/gateway/notification/TelegramNotificationService.java:104-106` (translation pattern)
- Modify: `src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java:32-33`

**Interfaces:**
- Consumes: nothing new. No behavior contract changes — string/timeout adjustments only.

- [ ] **Step 1: Fix the mislabeled DLQ reason**

In `OracleRequestConsumer.handleSuccess` (line 184) the catch hardcodes "Response too large for Kafka" but the exception can equally be the 10s broker timeout. Replace:

```java
                    .failureReason("Response too large for Kafka: " + e.getMessage())
```

with:

```java
                    .failureReason("Failed to publish response to Kafka: " + e.getMessage())
```

Apply the identical replacement in `ApiRequestConsumer.handleSuccess` (same line in the mirror file; the DLQ there is built with `sendToApiDlq` — only the string changes).

- [ ] **Step 2: Keep the Telegram translation working**

In `TelegramNotificationService.translateFailureReason`, ABOVE the existing `"Response too large for Kafka:"` block (keep it — old DLQ messages may replay), add:

```java
        // Kafka publish failed (broker down, timeout, or message too large)
        if (reason.startsWith("Failed to publish response to Kafka:")) {
            return reason.replace("Failed to publish response to Kafka:",
                    "Не удалось опубликовать ответ в Kafka:");
        }
```

- [ ] **Step 3: BiruniClient connect timeout**

Replace the factory construction (lines 32-33):

```java
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectionTimeout()))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.getConnectionTimeout()));
```

- [ ] **Step 4: Verify build + tests**

Run: `mvn test -q`
Expected: PASS (compile-level change; existing tests still green).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix: honest Kafka-publish DLQ reason; BiruniClient connect timeout"
```

---

### Task 12: Documentation sync + version bump

**Files:**
- Modify: `ai-context/SAFETY-RULES.md`, `ai-context/CONFIGURATION.md`, `ai-context/ARCHITECTURE.md`
- Modify: `VERSION.md`
- Modify: `CLAUDE.md` (only if a Top-5 rule wording changed — none should)

**Interfaces:** none (docs).

- [ ] **Step 1: SAFETY-RULES.md — add new rules 25-28**

Append after rule 24, following the existing numbered format:

```markdown
### 25. Never ack a request message when the Redis idempotency gate itself fails

`OracleRequestConsumer`/`ApiRequestConsumer` wrap `isCompleted`+`tryLock` in a dedicated
try/catch that calls `ack.nack(Duration.ofSeconds(5))` and returns. Acking on a Redis
outage silently drops the message (pre-A-faza bug). Do not "simplify" this into the
generic catch below it — that one acks.

### 26. Never swallow RocksDB write failures

`RocksDBClient.putResponse`/`putResponseMetadata` throw `StorageWriteException` on failure
and `ResponseStorageService.saveResponse` propagates it. Response consumers catch it and
keep `hasResult=false`. Swallowing the exception re-creates phantom COMPLETED states whose
`/result` returns 404.

### 27. Never release a Redis lock without ownership check

Both `RequestStateService.releaseLock` and `OAuth2TokenRedisCache.releaseLock` use a
compare-and-delete Lua script against the owner token issued at acquire time. A blind
`DEL` deletes another instance's lock after TTL takeover → concurrent processing.

### 28. Never forward X-Callback-Url without CallbackUrlValidator

`ProxyController` validates the callback URL at accept time: http/https only, public
addresses only, optional `gateway.security.callback-allowed-hosts` allowlist. Removing the
check reopens SSRF (internal endpoints, cloud metadata) via a client-controlled header.
```

- [ ] **Step 2: CONFIGURATION.md — document new/changed keys**

In the `gateway.security.*` section add rows:

```markdown
| `gateway.security.callback-allowed-hosts` | List<String> | `[]` (empty) | `CALLBACK_ALLOWED_HOSTS` | X-Callback-Url host allowlist; empty = any public host. Private/loopback always blocked. |
```

In the `gateway.proxy.*` section add:

```markdown
| `gateway.proxy.trusted-proxies` | List<String> | `127.0.0.1` | `TRUSTED_PROXIES` | Peers whose X-Forwarded-For/X-Real-IP are trusted. |
```

Update the Telegram table: `bot-token` default is now empty (mandatory env var), `chat-id` default `0`, and note "blank token disables the service with a WARN".
Update `gateway.source.username`/`password` defaults to empty + mark as mandatory env vars in the production table (add `SOURCE_USERNAME`, `SOURCE_PASSWORD`, and `CALLBACK_ALLOWED_HOSTS`/`TRUSTED_PROXIES` as optional).

- [ ] **Step 3: ARCHITECTURE.md — cross-cutting concerns**

In the "Idempotency (Redis)" section append one line:

```markdown
- **Gate failure (A-faza):** If Redis itself fails during `isCompleted`/`tryLock`, the consumer nacks (5s) instead of acking — the message is redelivered, never dropped.
- **Lock ownership (A-faza):** lock values are per-acquire owner tokens; release is compare-and-delete.
```

- [ ] **Step 4: Ghost-reference grep**

Run: `grep -rn "Response too large for Kafka" src/ ai-context/ --include="*.java" --include="*.md"`
Expected: only the `TelegramNotificationService` legacy-translation block and possibly SAFETY-RULES history mentions. No consumer should produce that string anymore.

Run: `grep -rn "8318506270" . --include="*.yml" --include="*.md" --include="*.java"`
Expected: no matches (old leaked token fully gone).

- [ ] **Step 5: Version bump**

`VERSION.md` → `1.3.0` (single line). Note: on merge to `master` this triggers the build.yml release workflow — that is intended for the A-faza release.

- [ ] **Step 6: Full verification**

Run: `mvn clean package -DskipTests -q && mvn test -q`
Expected: BUILD SUCCESS twice, all tests green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: SAFETY-RULES 25-28, config reference for A-faza fixes; bump version to 1.3.0"
```

---

## Yakuniy eslatmalar (executor uchun)

- Har taskdan keyin `mvn test -q` yashil bo'lishi shart — qizil holatda keyingi taskka o'tilmaydi.
- `ApiRequestConsumer`/`OracleRequestConsumer` ataylab dublikat (SAFETY-RULES §10) — ularni birlashtirish TAQIQLANADI; o'zgarishlar ikkala faylga alohida kiritiladi.
- Release (debug → master PR, tag) plan tugagach ALOHIDA qadam — foydalanuvchi qo'lda boshlaydi.
