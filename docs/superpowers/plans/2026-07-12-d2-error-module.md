# D2 Error Module (Loki-backed Log Aggregation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give qosid operators an Errors module that surfaces failures from three sources — the Kafka DLQ, the qosid JAR's own logs, and the Docker infra containers' logs — all aggregated through Loki and viewed via an admin-auth proxy + three SPA pages.

**Architecture:** The JAR logs to files (existing `gateway.log` + a new structured-JSON `dlq.log`); Promtail ships those files plus all Docker container logs to Loki (7-day retention). The SPA never touches Loki directly — a read-only admin-authed proxy (`/qosid/admin/api/logs`) runs LogQL `query_range` server-side and normalizes results. The E1–E5 pipeline is untouched; only a best-effort structured-log call is added to the two DLQ consumers.

**Tech Stack:** Java 21 / Spring Boot 3.3.0 (RestClient for the Loki proxy, Logback, Jackson), Grafana Loki + Promtail (Docker), React 18 + TypeScript + Vite + Ant Design (existing D0+D1 SPA).

## Global Constraints

- Backend package `uz.greenwhite.gateway`; admin API base `/qosid/admin/api` (existing admin `SecurityFilterChain` — session auth + SPA-CSRF).
- Loki stays internal (Docker network); the browser reaches it ONLY through the qosid proxy. No new browser-facing origin.
- Three log sources and their exact LogQL label selectors: DLQ → `{stream="dlq"}`; JAR → `{job="qosid-jar"}`; Docker → `{container=~"<name>"}`.
- DLQ is logged as one-line JSON via a dedicated `dlq-audit` logger → `${LOG_DIR}/dlq.log` (Promtail labels it `stream="dlq"`). The DLQ-audit write is BEST-EFFORT: it is wrapped in try/catch and must never break the DLQ consumer or change the MANUAL Kafka ack.
- View-only: no retry/resolve/delete. The proxy is read-only.
- Loki retention 7 days (Loki config, `retention_period: 168h`), no application cleanup job.
- Loki unreachable must degrade gracefully: the proxy returns `{entries: [], error: "..."}` with HTTP 200, never a 500.
- Loki base URL is `gateway.loki.url` (yaml default `http://loki:3100`), resolved per-request via `SettingsService` with the yaml value as fallback (settings override allowed).
- Default log level filter is `ERROR`; the UI may opt into `WARN`. Result limit default 200, max 1000; default time window last 1 hour.
- Existing suites stay green: backend 102 tests, frontend 22 tests. The SPA still builds into the single JAR (D0+D1 `frontend` Maven profile).
- Windows Maven: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" <args>` (add `-P '!frontend'` for backend-only). Frontend tests: from `frontend/`, `npm test -- <pattern>`.

---

### Task 1: DLQ structured-JSON logging (best-effort)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/logging/DlqAuditLogger.java`
- Test: `src/test/java/uz/greenwhite/gateway/logging/DlqAuditLoggerTest.java`
- Modify: `src/main/resources/logback-spring.xml` (add `DLQ_JSON` appender + `dlq-audit` logger), `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleDlqConsumer.java`, `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiDlqConsumer.java`

**Interfaces:**
- Consumes: existing `DlqMessage` (`model/kafka/DlqMessage.java`), the Spring `ObjectMapper` bean.
- Produces: `DlqAuditLogger` bean with `void record(DlqMessage)` (best-effort, never throws) and package-private `String toJson(DlqMessage) throws JsonProcessingException`. Both DLQ consumers call `dlqAuditLogger.record(message)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/uz/greenwhite/gateway/logging/DlqAuditLoggerTest.java`:
```java
package uz.greenwhite.gateway.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.model.kafka.DlqMessage;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlqAuditLoggerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private DlqMessage sample() {
        return DlqMessage.builder()
                .tenantId(100L).requestId(7L).originalTopic("api-request")
                .failureReason("timeout").errorSource("HTTP").httpStatus(504)
                .attemptCount(3).url("POST http://x/y").body("payload")
                .failedAt(LocalDateTime.of(2026, 7, 12, 10, 0)).build();
    }

    @Test
    void toJsonContainsAllFieldsOnOneLine() throws JsonProcessingException {
        String json = new DlqAuditLogger(mapper).toJson(sample());
        assertThat(json).doesNotContain("\n");
        assertThat(json).contains("\"failureReason\":\"timeout\"")
                .contains("\"tenantId\":100").contains("\"httpStatus\":504")
                .contains("\"errorSource\":\"HTTP\"");
    }

    @Test
    void recordSwallowsSerializationFailure() throws JsonProcessingException {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("boom"));
        DlqAuditLogger logger = new DlqAuditLogger(broken);
        assertThatCode(() -> logger.record(sample())).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -P '!frontend' -Dtest=DlqAuditLoggerTest test`
Expected: FAIL — `DlqAuditLogger` does not exist (compile error).

- [ ] **Step 3: Implement `DlqAuditLogger`**

`src/main/java/uz/greenwhite/gateway/logging/DlqAuditLogger.java`:
```java
package uz.greenwhite.gateway.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.model.kafka.DlqMessage;

/**
 * Writes each DLQ failure as a single-line JSON record to the dedicated
 * {@code dlq-audit} logger (routed to {@code dlq.log} in logback-spring.xml,
 * additivity=false). Promtail labels that file {@code stream="dlq"} and the
 * Errors UI reads it back through the Loki proxy.
 *
 * Best-effort: a serialization or logging failure is swallowed so it can never
 * break the DLQ consumer or the Kafka MANUAL ack.
 */
@Component
@RequiredArgsConstructor
public class DlqAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("dlq-audit");
    private static final Logger LOG = LoggerFactory.getLogger(DlqAuditLogger.class);

    private final ObjectMapper objectMapper;

    public void record(DlqMessage message) {
        try {
            AUDIT.error(toJson(message));
        } catch (Exception e) {
            LOG.warn("DLQ audit log skipped: {}", e.getMessage());
        }
    }

    String toJson(DlqMessage message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(message);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -P '!frontend' -Dtest=DlqAuditLoggerTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire the logback appender + logger**

In `src/main/resources/logback-spring.xml`, add this appender after the `FILE` appender (before the `<logger ...>` lines):
```xml
    <appender name="DLQ_JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/dlq.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/dlq.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>${MAX_FILE_SIZE}</maxFileSize>
            <maxHistory>${MAX_HISTORY}</maxHistory>
            <totalSizeCap>${TOTAL_SIZE_CAP}</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%msg%n</pattern>
        </encoder>
    </appender>
```
And add this logger next to the existing `<logger ...>` entries (so the JSON line goes ONLY to dlq.log, not gateway.log/console):
```xml
    <logger name="dlq-audit" level="ERROR" additivity="false">
        <appender-ref ref="DLQ_JSON"/>
    </logger>
```

- [ ] **Step 6: Call the audit logger from both DLQ consumers**

In `OracleDlqConsumer.java`: add the field and the call. Change the class to inject `DlqAuditLogger`:
```java
    private final NotificationService notificationService;
    private final uz.greenwhite.gateway.logging.DlqAuditLogger dlqAuditLogger;
```
and inside `consumeDlq`, after the alert try/catch and before `ack.acknowledge();`:
```java
        dlqAuditLogger.record(message);
```
Make the identical change in `ApiDlqConsumer.java` (same field, same call in the same place).

- [ ] **Step 7: Run the full backend suite**

Run: `mvn -P '!frontend' test`
Expected: `Tests run: 104` (102 + 2 new), 0 failures. (The two consumer classes gain a constructor arg; Spring injects the new bean — existing consumer tests, if any construct them directly, must pass the new arg. If a consumer test fails to compile, update it to pass a `new DlqAuditLogger(objectMapper)` or a mock.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/logging/ src/test/java/uz/greenwhite/gateway/logging/ src/main/resources/logback-spring.xml src/main/java/uz/greenwhite/gateway/kafka/consumer/
git commit -m "feat(errors): best-effort structured-JSON DLQ audit log for Loki"
```

---

### Task 2: Loki proxy backend (query API)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/logs/LogEntry.java`, `LogQueryResult.java`, `LogQlBuilder.java`, `LokiResponseParser.java`, `LogQueryService.java`, `src/main/java/uz/greenwhite/gateway/admin/LogQueryController.java`
- Test: `src/test/java/uz/greenwhite/gateway/logs/LogQlBuilderTest.java`, `LokiResponseParserTest.java`, `LogQueryServiceTest.java`, `src/test/java/uz/greenwhite/gateway/admin/LogQueryControllerTest.java`

**Interfaces:**
- Consumes: `SettingsService` (`settings/SettingsService.java`, method `String get(String key, String def)`); Spring `ObjectMapper`.
- Produces:
  - `record LogEntry(String timestamp, String level, String line, java.util.Map<String,String> labels, Object parsed)`.
  - `record LogQueryResult(java.util.List<LogEntry> entries, String error)`.
  - `LogQlBuilder.build(String source, String q, String container, String level) -> String` (static).
  - `LokiResponseParser.parse(String lokiJson) -> List<LogEntry>` and `.parseLabelValues(String lokiJson) -> List<String>`.
  - `LogQueryService.query(String source, Long fromMs, Long toMs, String q, String container, String level, Integer limit) -> LogQueryResult`; `.labelValues(String name) -> List<String>`.
  - `LogQueryController` at `/qosid/admin/api/logs` and `/qosid/admin/api/logs/labels`.

- [ ] **Step 1: Write the failing tests for the pure helpers**

`src/test/java/uz/greenwhite/gateway/logs/LogQlBuilderTest.java`:
```java
package uz.greenwhite.gateway.logs;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LogQlBuilderTest {

    @Test
    void dlqSelectorWithTextFilter() {
        assertThat(LogQlBuilder.build("dlq", "timeout", null, null))
                .isEqualTo("{stream=\"dlq\"} |= \"timeout\"");
    }

    @Test
    void jarSelectorFiltersByLevelLabel() {
        assertThat(LogQlBuilder.build("jar", null, null, "ERROR"))
                .isEqualTo("{job=\"qosid-jar\",level=\"ERROR\"}");
    }

    @Test
    void dockerSelectorByContainerWithLevelLineFilter() {
        // Docker container log levels are not reliably labeled, so level is a
        // case-insensitive line filter, not a label.
        assertThat(LogQlBuilder.build("docker", null, "redis", "ERROR"))
                .isEqualTo("{container=~\"redis\"} |~ \"(?i)error\"");
    }

    @Test
    void unknownSourceThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LogQlBuilder.build("bogus", null, null, null));
    }
}
```

`src/test/java/uz/greenwhite/gateway/logs/LokiResponseParserTest.java`:
```java
package uz.greenwhite.gateway.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LokiResponseParserTest {

    private final LokiResponseParser parser = new LokiResponseParser(new ObjectMapper());

    @Test
    void parsesStreamsValuesNewestFirst() {
        String json = """
            {"data":{"result":[
              {"stream":{"level":"ERROR","job":"qosid-jar"},
               "values":[["1700000001000000000","older line"],["1700000002000000000","newer line"]]}
            ]}}""";
        List<LogEntry> entries = parser.parse(json);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).line()).isEqualTo("newer line"); // newest first
        assertThat(entries.get(0).level()).isEqualTo("ERROR");
        assertThat(entries.get(0).labels()).containsEntry("job", "qosid-jar");
        assertThat(entries.get(0).timestamp()).startsWith("2023-11-14T"); // ns -> ISO
    }

    @Test
    void parsedFieldHoldsDlqJsonWhenLineIsJson() {
        String json = """
            {"data":{"result":[
              {"stream":{"stream":"dlq"},
               "values":[["1700000002000000000","{\\"failureReason\\":\\"timeout\\",\\"tenantId\\":100}"]]}
            ]}}""";
        List<LogEntry> entries = parser.parse(json);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).parsed()).isNotNull();
    }

    @Test
    void parseLabelValuesReturnsData() {
        assertThat(parser.parseLabelValues("{\"data\":[\"redis\",\"redpanda\"]}"))
                .containsExactly("redis", "redpanda");
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -P '!frontend' -Dtest=LogQlBuilderTest,LokiResponseParserTest test`
Expected: FAIL — classes not found.

- [ ] **Step 3: Implement the records and pure helpers**

`src/main/java/uz/greenwhite/gateway/logs/LogEntry.java`:
```java
package uz.greenwhite.gateway.logs;

import java.util.Map;

public record LogEntry(String timestamp, String level, String line, Map<String, String> labels, Object parsed) {}
```

`src/main/java/uz/greenwhite/gateway/logs/LogQueryResult.java`:
```java
package uz.greenwhite.gateway.logs;

import java.util.List;

public record LogQueryResult(List<LogEntry> entries, String error) {}
```

`src/main/java/uz/greenwhite/gateway/logs/LogQlBuilder.java`:
```java
package uz.greenwhite.gateway.logs;

/** Builds the LogQL selector for each of the three error sources. */
public final class LogQlBuilder {

    private LogQlBuilder() {}

    public static String build(String source, String q, String container, String level) {
        String base = switch (source == null ? "" : source) {
            case "dlq" -> "{stream=\"dlq\"}";
            case "jar" -> (level == null || level.isBlank())
                    ? "{job=\"qosid-jar\"}"
                    : "{job=\"qosid-jar\",level=\"" + level + "\"}";
            case "docker" -> {
                String sel = (container == null || container.isBlank())
                        ? "{container=~\".+\"}"
                        : "{container=~\"" + container + "\"}";
                yield (level == null || level.isBlank())
                        ? sel
                        : sel + " |~ \"(?i)" + level.toLowerCase() + "\"";
            }
            default -> throw new IllegalArgumentException("unknown source: " + source);
        };
        if (q != null && !q.isBlank()) {
            base = base + " |= \"" + q + "\"";
        }
        return base;
    }
}
```

`src/main/java/uz/greenwhite/gateway/logs/LokiResponseParser.java`:
```java
package uz.greenwhite.gateway.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses Loki query_range / label-values JSON into normalized {@link LogEntry} rows. */
@RequiredArgsConstructor
public class LokiResponseParser {

    private final ObjectMapper objectMapper;

    public List<LogEntry> parse(String lokiJson) {
        List<LogEntry> out = new ArrayList<>();
        try {
            JsonNode result = objectMapper.readTree(lokiJson).path("data").path("result");
            for (JsonNode stream : result) {
                Map<String, String> labels = new LinkedHashMap<>();
                stream.path("stream").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                String level = labels.getOrDefault("level", "");
                for (JsonNode value : stream.path("values")) {
                    String tsNs = value.get(0).asText();
                    String line = value.get(1).asText();
                    out.add(new LogEntry(nsToIso(tsNs), level, line, labels, tryParseJson(line)));
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        out.sort(Comparator.comparing(LogEntry::timestamp).reversed()); // newest first
        return out;
    }

    public List<String> parseLabelValues(String lokiJson) {
        List<String> out = new ArrayList<>();
        try {
            objectMapper.readTree(lokiJson).path("data").forEach(n -> out.add(n.asText()));
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private String nsToIso(String tsNs) {
        long ns = Long.parseLong(tsNs);
        return Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L).toString();
    }

    private Object tryParseJson(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("{")) return null;
        try {
            return objectMapper.readValue(trimmed, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run to verify the helper tests pass**

Run: `mvn -P '!frontend' -Dtest=LogQlBuilderTest,LokiResponseParserTest test`
Expected: PASS (4 + 3).

- [ ] **Step 5: Write the failing service test (Loki-down + happy path)**

`src/test/java/uz/greenwhite/gateway/logs/LogQueryServiceTest.java`:
```java
package uz.greenwhite.gateway.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.settings.SettingsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.hamcrest.Matchers;

class LogQueryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsService settings = mock(SettingsService.class);

    private LogQueryService serviceBackedBy(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        when(settings.get("gateway.loki.url", "http://loki:3100")).thenReturn("http://loki:3100");
        return new LogQueryService(builder.build(), new LokiResponseParser(mapper), settings);
    }

    @Test
    void happyPathReturnsEntriesAndNoError() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        LogQueryService service = serviceBackedBy(holder);
        holder[0].expect(requestTo(Matchers.containsString("/loki/api/v1/query_range")))
                .andRespond(withSuccess(
                        "{\"data\":{\"result\":[{\"stream\":{\"level\":\"ERROR\"},\"values\":[[\"1700000002000000000\",\"boom\"]]}]}}",
                        MediaType.APPLICATION_JSON));
        LogQueryResult result = service.query("jar", null, null, null, null, "ERROR", 200);
        assertThat(result.error()).isNull();
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).line()).isEqualTo("boom");
    }

    @Test
    void lokiDownReturnsEmptyWithErrorNotException() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        LogQueryService service = serviceBackedBy(holder);
        holder[0].expect(requestTo(Matchers.containsString("/loki/api/v1/query_range")))
                .andRespond(withServerError());
        LogQueryResult result = service.query("jar", null, null, null, null, "ERROR", 200);
        assertThat(result.entries()).isEmpty();
        assertThat(result.error()).isNotNull();
    }
}
```

- [ ] **Step 6: Implement `LogQueryService`**

`src/main/java/uz/greenwhite/gateway/logs/LogQueryService.java`:
```java
package uz.greenwhite.gateway.logs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.List;

/**
 * Read-only proxy over Loki's HTTP API. Resolves the Loki base URL per call
 * (settings override, yaml fallback), runs a LogQL query_range, and normalizes
 * the result. Loki being unreachable degrades to an empty result + error string
 * (never a 500) so the Errors UI stays usable when logging infra is down.
 */
@Slf4j
@Service
public class LogQueryService {

    private static final String DEFAULT_URL = "http://loki:3100";
    private static final int MAX_LIMIT = 1000;

    private final RestClient lokiRestClient;
    private final LokiResponseParser parser;
    private final SettingsService settings;

    public LogQueryService(RestClient lokiRestClient, LokiResponseParser parser, SettingsService settings) {
        this.lokiRestClient = lokiRestClient;
        this.parser = parser;
        this.settings = settings;
    }

    public LogQueryResult query(String source, Long fromMs, Long toMs, String q,
                                String container, String level, Integer limit) {
        long end = (toMs != null) ? toMs : System.currentTimeMillis();
        long start = (fromMs != null) ? fromMs : end - 3_600_000L; // default 1h
        int lim = (limit == null) ? 200 : Math.min(Math.max(limit, 1), MAX_LIMIT);
        String logql = LogQlBuilder.build(source, q, container, level);
        String uri = UriComponentsBuilder.fromUriString(baseUrl() + "/loki/api/v1/query_range")
                .queryParam("query", logql)
                .queryParam("start", start * 1_000_000L)   // ms -> ns
                .queryParam("end", end * 1_000_000L)
                .queryParam("limit", lim)
                .queryParam("direction", "backward")
                .build().toUriString();
        try {
            String body = lokiRestClient.get().uri(uri).retrieve().body(String.class);
            return new LogQueryResult(parser.parse(body), null);
        } catch (Exception e) {
            log.warn("Loki query failed: {}", e.getMessage());
            return new LogQueryResult(List.of(), "Loki unreachable: " + e.getMessage());
        }
    }

    public List<String> labelValues(String name) {
        String uri = baseUrl() + "/loki/api/v1/label/" + name + "/values";
        try {
            String body = lokiRestClient.get().uri(uri).retrieve().body(String.class);
            return parser.parseLabelValues(body);
        } catch (Exception e) {
            log.warn("Loki label query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String baseUrl() {
        return settings.get("gateway.loki.url", DEFAULT_URL);
    }
}
```

Add the `RestClient` bean. Create `src/main/java/uz/greenwhite/gateway/logs/LokiClientConfig.java`:
```java
package uz.greenwhite.gateway.logs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LokiClientConfig {

    /** Bare RestClient — the absolute Loki URI is built per-request from settings. */
    @Bean
    public RestClient lokiRestClient() {
        return RestClient.builder().build();
    }
}
```

- [ ] **Step 7: Run to verify the service test passes**

Run: `mvn -P '!frontend' -Dtest=LogQueryServiceTest test`
Expected: PASS (2).

- [ ] **Step 8: Write the failing controller test (auth-gate + params)**

`src/test/java/uz/greenwhite/gateway/admin/LogQueryControllerTest.java`:
```java
package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import uz.greenwhite.gateway.logs.LogEntry;
import uz.greenwhite.gateway.logs.LogQueryResult;
import uz.greenwhite.gateway.logs.LogQueryService;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LogQueryControllerTest {

    @Autowired MockMvc mvc;
    @MockBean LogQueryService logQueryService;

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/logs?source=jar")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void returnsEntriesForAuthenticatedAdmin() throws Exception {
        when(logQueryService.query(eq("dlq"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LogQueryResult(
                        List.of(new LogEntry("2026-07-12T10:00:00Z", "ERROR", "boom", Map.of(), null)), null));
        mvc.perform(get("/qosid/admin/api/logs?source=dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].line").value("boom"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void labelValuesEndpoint() throws Exception {
        when(logQueryService.labelValues("container")).thenReturn(List.of("redis", "redpanda"));
        mvc.perform(get("/qosid/admin/api/logs/labels?name=container"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value("redis"));
    }
}
```

- [ ] **Step 9: Implement `LogQueryController`**

`src/main/java/uz/greenwhite/gateway/admin/LogQueryController.java`:
```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.gateway.logs.LogQueryResult;
import uz.greenwhite.gateway.logs.LogQueryService;

import java.util.Map;

/**
 * Read-only admin proxy over Loki. Under the /qosid/admin/api/** chain, so it is
 * session-authenticated + CSRF-protected like the rest of the control plane.
 */
@RestController
@RequestMapping("/qosid/admin/api/logs")
@RequiredArgsConstructor
public class LogQueryController {

    private final LogQueryService logQueryService;

    @GetMapping
    public ResponseEntity<LogQueryResult> query(
            @RequestParam String source,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String container,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(logQueryService.query(source, from, to, q, container, level, limit));
    }

    @GetMapping("/labels")
    public ResponseEntity<Map<String, Object>> labels(@RequestParam String name) {
        return ResponseEntity.ok(Map.of("values", logQueryService.labelValues(name)));
    }
}
```

Add the yaml default in `src/main/resources/application.yml` under the `gateway:` tree:
```yaml
  loki:
    url: ${LOKI_URL:http://loki:3100}
```

- [ ] **Step 10: Run the controller test + full backend suite**

Run: `mvn -P '!frontend' -Dtest=LogQueryControllerTest test` → Expected: PASS (3).
Run: `mvn -P '!frontend' test` → Expected: `Tests run: 116` (104 + 12 new), 0 failures.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/logs/ src/main/java/uz/greenwhite/gateway/admin/LogQueryController.java src/test/java/uz/greenwhite/gateway/logs/ src/test/java/uz/greenwhite/gateway/admin/LogQueryControllerTest.java src/main/resources/application.yml
git commit -m "feat(errors): read-only Loki proxy API (/qosid/admin/api/logs)"
```

---

### Task 3: Infra — Loki + Promtail (docker-compose)

**Files:**
- Create: `docker/loki-config.yml`, `docker/promtail-config.yml`
- Modify: `docker-compose.yml` (add `loki` + `promtail` services and a `loki_data` volume)

**Interfaces:**
- Produces: a `loki` service reachable at `http://loki:3100` on the compose network, fed by `promtail` which tails the JAR log dir + Docker container logs. No code depends on this task at compile time; the Loki proxy (Task 2) talks to it at runtime and the Errors UI shows its data.

- [ ] **Step 1: Create the Loki config**

`docker/loki-config.yml`:
```yaml
auth_enabled: false
server:
  http_listen_port: 3100
common:
  instance_addr: 127.0.0.1
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory
schema_config:
  configs:
    - from: 2020-10-24
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h
limits_config:
  retention_period: 168h        # 7 days
  reject_old_samples: true
  reject_old_samples_max_age: 168h
compactor:
  working_directory: /loki/compactor
  delete_request_store: filesystem
  retention_enabled: true
```

- [ ] **Step 2: Create the Promtail config**

`docker/promtail-config.yml` (three scrape jobs: JAR file, DLQ file, Docker containers):
```yaml
server:
  http_listen_port: 9080
positions:
  filename: /tmp/positions.yaml
clients:
  - url: http://loki:3100/loki/api/v1/push
scrape_configs:
  - job_name: qosid-jar
    static_configs:
      - targets: [localhost]
        labels:
          job: qosid-jar
          __path__: /var/log/qosid/gateway.log
    pipeline_stages:
      - regex:
          expression: '^\S+ \S+ (?P<level>\S+) '
      - labels:
          level:
  - job_name: dlq
    static_configs:
      - targets: [localhost]
        labels:
          stream: dlq
          __path__: /var/log/qosid/dlq.log
    pipeline_stages:
      - json:
          expressions:
            failureReason: failureReason
  - job_name: docker
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 15s
    relabel_configs:
      - source_labels: ['__meta_docker_container_name']
        regex: '/(.*)'
        target_label: container
```

- [ ] **Step 3: Add the services to docker-compose.yml**

Append these two services under the existing `services:` map, and add `loki_data:` under the top-level `volumes:` map. (Match the file's existing indentation.) `${LOG_DIR}` on the host — where the systemd JAR writes `gateway.log`/`dlq.log` — is mounted read-only into Promtail; adjust `/host/qosid-logs` to the real host path at deploy time.
```yaml
  loki:
    image: grafana/loki:3.1.0
    container_name: loki
    command: -config.file=/etc/loki/loki-config.yml
    ports:
      - "3100:3100"
    volumes:
      - ./docker/loki-config.yml:/etc/loki/loki-config.yml:ro
      - loki_data:/loki
    restart: unless-stopped

  promtail:
    image: grafana/promtail:3.1.0
    container_name: promtail
    command: -config.file=/etc/promtail/promtail-config.yml
    volumes:
      - ./docker/promtail-config.yml:/etc/promtail/promtail-config.yml:ro
      - /host/qosid-logs:/var/log/qosid:ro          # <-- host LOG_DIR (gateway.log, dlq.log)
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - /var/lib/docker/containers:/var/lib/docker/containers:ro
    depends_on:
      - loki
    restart: unless-stopped
```
And under `volumes:`:
```yaml
  loki_data:
```

- [ ] **Step 4: Validate the compose file**

Run: `docker compose config -q`
Expected: exits 0 (no output) — the YAML is valid and the new services parse. (If Docker is unavailable in this environment, instead run a YAML lint: `python -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ['docker-compose.yml','docker/loki-config.yml','docker/promtail-config.yml']]; print('ok')"` — expect `ok`.)

- [ ] **Step 5: Commit**

```bash
git add docker/loki-config.yml docker/promtail-config.yml docker-compose.yml
git commit -m "feat(errors): Loki + Promtail infra (JAR + DLQ + Docker log scraping)"
```

---

### Task 4: Frontend — logs API, shared LogTable, Errors nav, DLQ page

**Files:**
- Create: `frontend/src/api/logs.ts`, `frontend/src/pages/errors/LogTable.tsx`, `frontend/src/pages/errors/LogFilters.tsx`, `frontend/src/pages/errors/DlqPage.tsx`
- Modify: `frontend/src/layout/AppLayout.tsx` (enable Errors nav → submenu), `frontend/src/App.tsx` (routes `/errors/dlq|jar|docker`), `frontend/src/locales/uz.json` (error keys)
- Test: `frontend/src/pages/errors/DlqPage.test.tsx`

**Interfaces:**
- Consumes: `api` (`api/client.ts`), `useTranslation`.
- Produces:
  - `frontend/src/api/logs.ts`: `interface LogEntry { timestamp: string; level: string; line: string; labels: Record<string,string>; parsed?: any }`; `interface LogQueryResult { entries: LogEntry[]; error?: string }`; `queryLogs(params) : Promise<LogQueryResult>`; `getLabelValues(name: string): Promise<string[]>`.
  - `LogTable` (generic entry table + detail drawer), `LogFilters` (time range + text + level), `DlqPage`. `JarPage`/`DockerPage` (Task 5) reuse `LogTable`/`LogFilters`.

- [ ] **Step 1: Write the failing DLQ page test**

`frontend/src/pages/errors/DlqPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { DlqPage } from './DlqPage';
import * as logsApi from '../../api/logs';

beforeEach(() => vi.restoreAllMocks());

describe('DlqPage', () => {
  it('renders parsed DLQ fields from Loki entries', async () => {
    vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({
      entries: [{
        timestamp: '2026-07-12T10:00:00Z', level: 'ERROR',
        line: '{"failureReason":"timeout","tenantId":100,"httpStatus":504}',
        labels: { stream: 'dlq' },
        parsed: { failureReason: 'timeout', tenantId: 100, httpStatus: 504 },
      }],
    });
    render(<DlqPage />);
    await waitFor(() => expect(screen.getByText('timeout')).toBeInTheDocument());
    expect(screen.getByText('504')).toBeInTheDocument();
  });

  it('shows the Loki-unreachable error banner', async () => {
    vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({ entries: [], error: 'Loki unreachable' });
    render(<DlqPage />);
    await waitFor(() => expect(screen.getByText(/Loki unreachable/)).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run (from `frontend/`): `npm test -- DlqPage`
Expected: FAIL — `./DlqPage`, `../../api/logs` not found.

- [ ] **Step 3: Implement the logs API wrapper**

`frontend/src/api/logs.ts`:
```ts
import { api } from './client';

const BASE = '/qosid/admin/api/logs';

export interface LogEntry {
  timestamp: string;
  level: string;
  line: string;
  labels: Record<string, string>;
  parsed?: any;
}
export interface LogQueryResult { entries: LogEntry[]; error?: string }

export interface LogQueryParams {
  source: 'dlq' | 'jar' | 'docker';
  from?: number;
  to?: number;
  q?: string;
  container?: string;
  level?: string;
  limit?: number;
}

export const queryLogs = (params: LogQueryParams) =>
  api.get<LogQueryResult>(BASE, { params }).then(r => r.data);

export const getLabelValues = (name: string) =>
  api.get<{ values: string[] }>(`${BASE}/labels`, { params: { name } }).then(r => r.data.values);
```

- [ ] **Step 4: Implement LogFilters and LogTable**

`frontend/src/pages/errors/LogFilters.tsx`:
```tsx
import { Space, Input, Select, DatePicker, Button } from 'antd';
import { useTranslation } from 'react-i18next';
import type { Dayjs } from 'dayjs';

export interface FilterState {
  q: string;
  level: string;
  range: [Dayjs, Dayjs] | null;
}

export function LogFilters({
  value, onChange, onRefresh, showLevel = true, extra,
}: {
  value: FilterState;
  onChange: (v: FilterState) => void;
  onRefresh: () => void;
  showLevel?: boolean;
  extra?: React.ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <Space wrap style={{ marginBottom: 12 }}>
      {extra}
      {showLevel && (
        <Select
          style={{ width: 120 }}
          value={value.level}
          onChange={(level) => onChange({ ...value, level })}
          options={[
            { value: 'ERROR', label: 'ERROR' },
            { value: 'WARN', label: 'WARN' },
            { value: '', label: t('errors.allLevels') },
          ]}
        />
      )}
      <DatePicker.RangePicker
        showTime
        value={value.range as any}
        onChange={(range) => onChange({ ...value, range: range as any })}
      />
      <Input.Search
        allowClear
        placeholder={t('errors.searchText')}
        style={{ width: 200 }}
        onSearch={(q) => onChange({ ...value, q })}
      />
      <Button onClick={onRefresh}>{t('errors.refresh')}</Button>
    </Space>
  );
}
```

`frontend/src/pages/errors/LogTable.tsx`:
```tsx
import { useState } from 'react';
import { Table, Drawer, Tag, Alert } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import type { LogEntry } from '../../api/logs';

const levelColor: Record<string, string> = { ERROR: 'red', WARN: 'gold', INFO: 'blue' };

export function LogTable({
  entries, loading, error, columns,
}: {
  entries: LogEntry[];
  loading: boolean;
  error?: string;
  columns: ColumnsType<LogEntry>;
}) {
  const { t } = useTranslation();
  const [detail, setDetail] = useState<LogEntry | null>(null);

  return (
    <>
      {error && <Alert type="error" style={{ marginBottom: 12 }} message={error} />}
      <Table<LogEntry>
        size="small"
        loading={loading}
        rowKey={(_r, i) => String(i)}
        dataSource={entries}
        columns={columns}
        onRow={(r) => ({ onClick: () => setDetail(r), style: { cursor: 'pointer' } })}
        pagination={{ pageSize: 50 }}
      />
      <Drawer open={!!detail} title={t('errors.detail')} width={640} onClose={() => setDetail(null)}>
        {detail && (
          <>
            <p><Tag color={levelColor[detail.level] ?? 'default'}>{detail.level || '-'}</Tag> {detail.timestamp}</p>
            <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {detail.parsed ? JSON.stringify(detail.parsed, null, 2) : detail.line}
            </pre>
          </>
        )}
      </Drawer>
    </>
  );
}
```

- [ ] **Step 5: Implement the DLQ page**

`frontend/src/pages/errors/DlqPage.tsx`:
```tsx
import { useCallback, useEffect, useState } from 'react';
import { Card } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { queryLogs, LogEntry } from '../../api/logs';
import { LogTable } from './LogTable';
import { LogFilters, FilterState } from './LogFilters';

export function DlqPage() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();
  const [filter, setFilter] = useState<FilterState>({ q: '', level: '', range: null });

  const load = useCallback(() => {
    setLoading(true);
    queryLogs({
      source: 'dlq',
      q: filter.q || undefined,
      from: filter.range?.[0]?.valueOf(),
      to: filter.range?.[1]?.valueOf(),
    })
      .then((r) => { setEntries(r.entries); setError(r.error); })
      .catch(() => setError(t('errors.loadFailed')))
      .finally(() => setLoading(false));
  }, [filter, t]);

  useEffect(load, [load]);

  const columns: ColumnsType<LogEntry> = [
    { title: t('errors.time'), dataIndex: 'timestamp', width: 190 },
    { title: 'tenant', render: (_v, r) => r.parsed?.tenantId ?? '-' , width: 90 },
    { title: 'reqId', render: (_v, r) => r.parsed?.requestId ?? '-', width: 110 },
    { title: 'status', render: (_v, r) => r.parsed?.httpStatus ?? '-', width: 80 },
    { title: 'source', render: (_v, r) => r.parsed?.errorSource ?? '-', width: 90 },
    { title: t('errors.reason'), render: (_v, r) => r.parsed?.failureReason ?? r.line },
  ];

  return (
    <Card title={t('errors.dlqTitle')}>
      <LogFilters value={filter} onChange={setFilter} onRefresh={load} showLevel={false} />
      <LogTable entries={entries} loading={loading} error={error} columns={columns} />
    </Card>
  );
}
```

- [ ] **Step 6: Enable the Errors nav + routes + i18n keys**

In `frontend/src/layout/AppLayout.tsx`, replace the disabled `errors` nav item with an enabled submenu:
```tsx
    { key: '/errors', icon: <WarningOutlined />, label: t('nav.errors'), children: [
        { key: '/errors/dlq', label: t('errors.dlqNav') },
        { key: '/errors/jar', label: t('errors.jarNav') },
        { key: '/errors/docker', label: t('errors.dockerNav') },
    ] },
```
Keep `Statistics`/`Logs` disabled as before. Update the `onClick` handler so error routes navigate:
```tsx
            onClick={({ key }) => { if (key === '/settings' || key.startsWith('/errors/')) navigate(key); }}
```
And update `selectedKey` so an `/errors/*` path highlights the right child (leave the existing settings logic, add):
```tsx
  const selectedKey = location.pathname.startsWith('/settings') ? '/settings' : location.pathname;
```
(`location.pathname` already equals e.g. `/errors/dlq`, so the child highlights correctly.)

In `frontend/src/App.tsx`, add routes inside the authed `<Routes>` (next to `settings/*`):
```tsx
                    <Route path="errors/dlq" element={<DlqPage />} />
                    <Route path="errors/jar" element={<JarPage />} />
                    <Route path="errors/docker" element={<DockerPage />} />
```
Import them at the top of `App.tsx`:
```tsx
import { DlqPage } from './pages/errors/DlqPage';
import { JarPage } from './pages/errors/JarPage';
import { DockerPage } from './pages/errors/DockerPage';
```
To keep THIS task building green, create stubs `frontend/src/pages/errors/JarPage.tsx` and `DockerPage.tsx` now: `export function JarPage() { return null; }` and `export function DockerPage() { return null; }` — Task 5 replaces them.

Add these keys to `frontend/src/locales/uz.json`:
```json
  "errors.dlqNav": "DLQ",
  "errors.jarNav": "Ilova loglari",
  "errors.dockerNav": "Docker loglari",
  "errors.dlqTitle": "DLQ xatolar",
  "errors.jarTitle": "Ilova (JAR) loglari",
  "errors.dockerTitle": "Docker service loglari",
  "errors.time": "Vaqt",
  "errors.reason": "Sabab",
  "errors.detail": "Tafsilot",
  "errors.searchText": "Matn qidiruv",
  "errors.refresh": "Yangilash",
  "errors.allLevels": "Barchasi",
  "errors.container": "Konteyner",
  "errors.loadFailed": "Yuklab bo'lmadi"
```

- [ ] **Step 7: Run the DLQ test + build**

Run (from `frontend/`): `npm test -- DlqPage` → Expected: PASS (2).
Run (from `frontend/`): `npm run build` → Expected: succeeds.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/logs.ts frontend/src/pages/errors/ frontend/src/layout/AppLayout.tsx frontend/src/App.tsx frontend/src/locales/uz.json
git commit -m "feat(ui): errors nav + logs API + shared LogTable + DLQ page"
```

---

### Task 5: Frontend — JAR page + Docker page

**Files:**
- Create (replace Task 4 stubs): `frontend/src/pages/errors/JarPage.tsx`, `frontend/src/pages/errors/DockerPage.tsx`
- Test: `frontend/src/pages/errors/JarPage.test.tsx`, `frontend/src/pages/errors/DockerPage.test.tsx`

**Interfaces:**
- Consumes: `queryLogs`, `getLabelValues`, `LogEntry` (Task 4 `api/logs.ts`), `LogTable`, `LogFilters`.
- Produces: `JarPage`, `DockerPage` (replace the return-null stubs).

- [ ] **Step 1: Write the failing tests**

`frontend/src/pages/errors/JarPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { JarPage } from './JarPage';
import * as logsApi from '../../api/logs';

beforeEach(() => vi.restoreAllMocks());

describe('JarPage', () => {
  it('queries source=jar with default level ERROR and renders lines', async () => {
    const spy = vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: 'NPE at Foo', labels: { job: 'qosid-jar' } }],
    });
    render(<JarPage />);
    await waitFor(() => expect(screen.getByText('NPE at Foo')).toBeInTheDocument());
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({ source: 'jar', level: 'ERROR' }));
  });
});
```

`frontend/src/pages/errors/DockerPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { DockerPage } from './DockerPage';
import * as logsApi from '../../api/logs';

beforeEach(() => vi.restoreAllMocks());

describe('DockerPage', () => {
  it('loads container names and renders docker log lines', async () => {
    vi.spyOn(logsApi, 'getLabelValues').mockResolvedValue(['redis', 'redpanda']);
    vi.spyOn(logsApi, 'queryLogs').mockResolvedValue({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: 'connection refused', labels: { container: 'redis' } }],
    });
    render(<DockerPage />);
    await waitFor(() => expect(screen.getByText('connection refused')).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run (from `frontend/`): `npm test -- JarPage DockerPage`
Expected: FAIL — stub pages render null, assertions fail.

- [ ] **Step 3: Implement the JAR page**

`frontend/src/pages/errors/JarPage.tsx`:
```tsx
import { useCallback, useEffect, useState } from 'react';
import { Card } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { queryLogs, LogEntry } from '../../api/logs';
import { LogTable } from './LogTable';
import { LogFilters, FilterState } from './LogFilters';

export function JarPage() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();
  const [filter, setFilter] = useState<FilterState>({ q: '', level: 'ERROR', range: null });

  const load = useCallback(() => {
    setLoading(true);
    queryLogs({
      source: 'jar',
      level: filter.level || undefined,
      q: filter.q || undefined,
      from: filter.range?.[0]?.valueOf(),
      to: filter.range?.[1]?.valueOf(),
    })
      .then((r) => { setEntries(r.entries); setError(r.error); })
      .catch(() => setError(t('errors.loadFailed')))
      .finally(() => setLoading(false));
  }, [filter, t]);

  useEffect(load, [load]);

  const columns: ColumnsType<LogEntry> = [
    { title: t('errors.time'), dataIndex: 'timestamp', width: 190 },
    { title: 'level', dataIndex: 'level', width: 80 },
    { title: t('errors.reason'), dataIndex: 'line', ellipsis: true },
  ];

  return (
    <Card title={t('errors.jarTitle')}>
      <LogFilters value={filter} onChange={setFilter} onRefresh={load} />
      <LogTable entries={entries} loading={loading} error={error} columns={columns} />
    </Card>
  );
}
```

- [ ] **Step 4: Implement the Docker page**

`frontend/src/pages/errors/DockerPage.tsx`:
```tsx
import { useCallback, useEffect, useState } from 'react';
import { Card, Select } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { queryLogs, getLabelValues, LogEntry } from '../../api/logs';
import { LogTable } from './LogTable';
import { LogFilters, FilterState } from './LogFilters';

export function DockerPage() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();
  const [containers, setContainers] = useState<string[]>([]);
  const [container, setContainer] = useState<string | undefined>();
  const [filter, setFilter] = useState<FilterState>({ q: '', level: 'ERROR', range: null });

  useEffect(() => { getLabelValues('container').then(setContainers).catch(() => setContainers([])); }, []);

  const load = useCallback(() => {
    setLoading(true);
    queryLogs({
      source: 'docker',
      container: container || undefined,
      level: filter.level || undefined,
      q: filter.q || undefined,
      from: filter.range?.[0]?.valueOf(),
      to: filter.range?.[1]?.valueOf(),
    })
      .then((r) => { setEntries(r.entries); setError(r.error); })
      .catch(() => setError(t('errors.loadFailed')))
      .finally(() => setLoading(false));
  }, [filter, container, t]);

  useEffect(load, [load]);

  const columns: ColumnsType<LogEntry> = [
    { title: t('errors.time'), dataIndex: 'timestamp', width: 190 },
    { title: t('errors.container'), render: (_v, r) => r.labels.container ?? '-', width: 140 },
    { title: t('errors.reason'), dataIndex: 'line', ellipsis: true },
  ];

  const containerSelect = (
    <Select
      allowClear
      style={{ width: 180 }}
      placeholder={t('errors.container')}
      value={container}
      onChange={setContainer}
      options={containers.map((c) => ({ value: c, label: c }))}
    />
  );

  return (
    <Card title={t('errors.dockerTitle')}>
      <LogFilters value={filter} onChange={setFilter} onRefresh={load} extra={containerSelect} />
      <LogTable entries={entries} loading={loading} error={error} columns={columns} />
    </Card>
  );
}
```

- [ ] **Step 5: Run the tests + full frontend suite + build**

Run (from `frontend/`): `npm test -- JarPage DockerPage` → Expected: PASS (1 + 1).
Run (from `frontend/`): `npm test` → Expected: all green (22 prior + DLQ 2 + JAR 1 + Docker 1 = 26).
Run (from `frontend/`): `npm run build` → Expected: succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/errors/JarPage.tsx frontend/src/pages/errors/JarPage.test.tsx frontend/src/pages/errors/DockerPage.tsx frontend/src/pages/errors/DockerPage.test.tsx
git commit -m "feat(ui): JAR log page + Docker container log page"
```

---

### Task 6: E2E + full build + local Loki smoke

**Files:**
- Create: `frontend/e2e/errors.spec.ts`
- (No new deps — Playwright + config exist from D0+D1 Task 8.)

**Interfaces:**
- Consumes: the built SPA (vite preview) with the admin API mocked at the network layer (`page.route`), exactly like `frontend/e2e/settings.spec.ts`.
- Produces: an E2E proving login → Errors → DLQ page renders a mocked log entry.

- [ ] **Step 1: Write the failing E2E**

`frontend/e2e/errors.spec.ts`:
```ts
import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/logs?*', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR',
        line: '{"failureReason":"timeout","tenantId":100,"httpStatus":504}',
        labels: { stream: 'dlq' },
        parsed: { failureReason: 'timeout', tenantId: 100, httpStatus: 504 } }] }) }));
});

test('login then view a DLQ error', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await page.goto('/errors/dlq');
  await expect(page.getByText('timeout')).toBeVisible();
});
```

- [ ] **Step 2: Run the E2E**

Run (from `frontend/`): `npm run e2e`
Expected: 2 passed (the D0+D1 settings spec + this errors spec). If chromium is missing: `npx playwright install chromium` then retry.

- [ ] **Step 3: Full single-JAR build + backend regression**

Run: `mvn package -DskipTests` (frontend profile ON)
Expected: BUILD SUCCESS; `jar tf target/qosid-flow-1.0.0-SNAPSHOT.jar | grep -E "static/index.html"` present.
Run: `mvn -P '!frontend' test`
Expected: 116 backend tests green (unchanged pipeline; Loki proxy + DLQ audit only).

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/errors.spec.ts
git commit -m "test(ui): E2E for the Errors DLQ page over mocked logs API"
```

---

## Phase-end verification (controller runs after all tasks)

1. `mvn -P '!frontend' test` — 116 backend tests green.
2. `cd frontend && npm test` — 26 frontend tests green.
3. `cd frontend && npm run e2e` — Playwright green.
4. `mvn package -DskipTests` — single JAR with `static/index.html`.
5. **Local Loki smoke:** `docker compose up -d loki promtail`, boot the JAR with `LOG_DIR` pointing at a dir Promtail mounts, POST a message that lands in the DLQ (or append a JSON line to `dlq.log`), open `/errors/dlq` in a browser after login → confirm the entry appears; append an ERROR line to `gateway.log` → confirm `/errors/jar`; check a running container's logs on `/errors/docker`. Screenshot each. Confirm Loki-down handling: stop `loki`, reload a page → error banner, not a 500.

## Self-Review notes (author)

- **Spec coverage:** DLQ structured JSON → Task 1; Loki proxy API + config + Loki-down + settings override → Task 2; Loki+Promtail infra + 7-day retention → Task 3; Errors nav + DLQ page → Task 4; JAR + Docker pages → Task 5; testing/E2E/smoke → Tasks 1-6 + phase-end. All spec §3-§8 map to a task. Server "errors" are JAR logs (spec §1), covered by the JAR page — no code capture, as agreed.
- **Type consistency:** `LogEntry`/`LogQueryResult` shapes match between backend records (Task 2) and the frontend `api/logs.ts` interfaces (Task 4): `{timestamp, level, line, labels, parsed}` / `{entries, error}`. `queryLogs` params match the controller's `@RequestParam` names (`source,from,to,q,container,level,limit`). Nav/route keys `/errors/dlq|jar|docker` consistent across AppLayout + App.
- **Best-effort invariant:** the only pipeline-touching change (Task 1) is a try/catch'd log call after the existing alert, before the unchanged `ack.acknowledge()` — MANUAL ack and consumer logic preserved.
- **Read-only invariant:** the proxy only GETs Loki; no write/retry/delete path exists.
- **Stub sequencing:** Task 4 creates `JarPage`/`DockerPage` stubs so `App.tsx` builds; Task 5 replaces them. Called out inline.
