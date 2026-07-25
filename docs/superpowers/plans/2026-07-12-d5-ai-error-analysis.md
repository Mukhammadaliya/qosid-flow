# D5 AI Error Analysis (opt-in) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On-demand AI root-cause + fix analysis for any D2 error (DLQ/JAR/Docker), via a read-only admin proxy to a configurable OpenAI-compatible LLM — opt-in and dormant until an API key is set.

**Architecture:** A backend `AiAnalysisService` redacts obvious secrets from the error context, builds a system+user chat prompt, and POSTs to a settings-configured OpenAI-compatible `/chat/completions` endpoint (RestClient, D2/D3 proxy pattern). An `AiController` exposes `/ai/status` (opt-in gate), `/ai/analyze`, and `/ai/config`. The SPA adds an "AI tahlil" button to the shared D2 `LogTable` drawer (shown only when enabled) and an AI config form in Settings. The E1–E5 pipeline is untouched.

**Tech Stack:** Java 21 / Spring Boot 3.3.0 (RestClient, Jackson), React 18 + TS + Vite + Ant Design (existing SPA), OpenAI-compatible chat API (external, user-configured).

## Global Constraints

- Backend package `uz.greenwhite.gateway`; admin API base `/qosid/admin/api` (existing session-auth + CSRF chain; GET auth-gated, 401 unauthenticated).
- **Opt-in / default-OFF:** the feature is enabled ONLY when all three of `gateway.ai.base-url`, `gateway.ai.api-key`, `gateway.ai.model` are non-blank. When not enabled: `/ai/status` returns `{enabled:false}`, the SPA shows no AI button, and `/ai/analyze` returns `{analysis:null, error:"AI not configured"}` — NO external call, NO cost.
- Config via `SettingsService` (B1): `get(key,def)`, `getSecret(key,def)` (decrypts), `set(key,value,secret,changedBy)`. `gateway.ai.api-key` is a secret (AES, `set(..., true, ...)`), masked `***` on read. NO `@Value` yaml fallback — empty config means disabled (do not invent a default endpoint/key).
- The LLM call is a POST to `{base-url}/chat/completions`, header `Authorization: Bearer <api-key>`, body `{model, messages:[{role:"system",...},{role:"user",...}], temperature:0.2, max_tokens:800}`; response `choices[0].message.content`.
- **Redaction before sending:** apply regex masking to the assembled context — `(?i)(bearer)\s+[A-Za-z0-9._\-]+` → `$1 ***`, and `(?i)(authorization|password|passwd|token|api[_-]?key|secret)(\s*[:=]\s*)(\S+)` → `$1$2***`.
- **never-500:** any LLM error/timeout, unreachable endpoint, or not-configured state degrades to `{analysis:null, error:"..."}` HTTP 200. The controller always `ResponseEntity.ok`.
- The config PUT stores `api-key` ONLY when the submitted value is non-blank AND `!= "***"` (never persist the mask — mirrors `SourceAuthController`).
- All UI copy via i18next `t('key')`; the AI button lives in the shared `LogTable` drawer and works for all three error sources.
- Existing suites stay green (backend 139, frontend 31); the SPA still builds into the single JAR (D0+D1 `frontend` profile).
- Windows Maven: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" <args>` (`-P '!frontend'` for backend-only). Frontend: from `frontend/`, `npm test -- <pattern>`.

---

### Task 1: AI backend — redaction, analysis service, controller, config

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/ai/AiRedactor.java`, `AiAnalyzeRequest.java`, `AiAnalysisResult.java`, `AiAnalysisService.java`, `AiClientConfig.java`, `src/main/java/uz/greenwhite/gateway/admin/AiController.java`, `src/main/java/uz/greenwhite/gateway/admin/dto/AiConfigRequest.java`
- Test: `src/test/java/uz/greenwhite/gateway/ai/AiRedactorTest.java`, `AiAnalysisServiceTest.java`, `src/test/java/uz/greenwhite/gateway/admin/AiControllerTest.java`

**Interfaces:**
- Consumes: `SettingsService.get(String,String)` / `getSecret(String,String)` / `set(String,String,boolean,String)`; Spring `ObjectMapper`.
- Produces:
  - `AiRedactor.redact(String) -> String` (static, pure).
  - `record AiAnalyzeRequest(String line, String level, java.util.Map<String,String> labels, com.fasterxml.jackson.databind.JsonNode parsed)`.
  - `record AiAnalysisResult(String analysis, String error)`.
  - `AiAnalysisService.enabled() -> boolean`; `.analyze(AiAnalyzeRequest) -> AiAnalysisResult`.
  - `AiController` at `/qosid/admin/api/ai`: `GET /status` → `{enabled}`, `POST /analyze` → `AiAnalysisResult`, `GET /config` → `{baseUrl, apiKey(masked), model}`, `PUT /config` (`AiConfigRequest`).

- [ ] **Step 1: Write the failing redactor test**

`src/test/java/uz/greenwhite/gateway/ai/AiRedactorTest.java`:
```java
package uz.greenwhite.gateway.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AiRedactorTest {

    @Test
    void masksBearerToken() {
        assertThat(AiRedactor.redact("GET failed, Authorization: Bearer abc.DEF-123 rejected"))
                .contains("Bearer ***").doesNotContain("abc.DEF-123");
    }

    @Test
    void masksKeyValueSecrets() {
        assertThat(AiRedactor.redact("password=hunter2 and token: xyz789 and api_key=k-42"))
                .contains("password=***").contains("token:***").contains("api_key=***")
                .doesNotContain("hunter2").doesNotContain("xyz789").doesNotContain("k-42");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        assertThat(AiRedactor.redact("connection timeout to supplier37 after 30s"))
                .isEqualTo("connection timeout to supplier37 after 30s");
    }

    @Test
    void nullSafe() {
        assertThat(AiRedactor.redact(null)).isEqualTo("");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -P '!frontend' -Dtest=AiRedactorTest test`
Expected: FAIL — `AiRedactor` not found.

- [ ] **Step 3: Implement the redactor + DTOs**

`src/main/java/uz/greenwhite/gateway/ai/AiRedactor.java`:
```java
package uz.greenwhite.gateway.ai;

import java.util.regex.Pattern;

/** Best-effort masking of obvious secrets before an error context is sent to an external LLM. */
public final class AiRedactor {

    private AiRedactor() {}

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer)\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern KV = Pattern.compile(
            "(?i)(authorization|password|passwd|token|api[_-]?key|secret)(\\s*[:=]\\s*)(\\S+)");

    public static String redact(String text) {
        if (text == null) return "";
        String out = BEARER.matcher(text).replaceAll("$1 ***");
        out = KV.matcher(out).replaceAll("$1$2***");
        return out;
    }
}
```

`src/main/java/uz/greenwhite/gateway/ai/AiAnalyzeRequest.java`:
```java
package uz.greenwhite.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record AiAnalyzeRequest(String line, String level, Map<String, String> labels, JsonNode parsed) {}
```

`src/main/java/uz/greenwhite/gateway/ai/AiAnalysisResult.java`:
```java
package uz.greenwhite.gateway.ai;

public record AiAnalysisResult(String analysis, String error) {}
```

- [ ] **Step 4: Run to verify the redactor test passes**

Run: `mvn -P '!frontend' -Dtest=AiRedactorTest test`
Expected: PASS (4).

- [ ] **Step 5: Write the failing service test**

`src/test/java/uz/greenwhite/gateway/ai/AiAnalysisServiceTest.java`:
```java
package uz.greenwhite.gateway.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.settings.SettingsService;
import org.hamcrest.Matchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAnalysisServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsService settings = mock(SettingsService.class);

    private AiAnalysisService service(MockRestServiceServer[] holder) {
        RestClient.Builder b = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(b).build();
        return new AiAnalysisService(b.build(), settings, mapper);
    }

    private void configure(boolean on) {
        when(settings.get("gateway.ai.base-url", "")).thenReturn(on ? "https://llm.example/v1" : "");
        when(settings.get("gateway.ai.api-key", "")).thenReturn(on ? "stored" : "");
        when(settings.get("gateway.ai.model", "")).thenReturn(on ? "gpt-4o-mini" : "");
        when(settings.getSecret("gateway.ai.api-key", "")).thenReturn(on ? "sk-real" : "");
    }

    @Test
    void disabledWhenConfigBlank() {
        configure(false);
        AiAnalysisService s = service(new MockRestServiceServer[1]);
        assertThat(s.enabled()).isFalse();
        AiAnalysisResult r = s.analyze(new AiAnalyzeRequest("boom", "ERROR", java.util.Map.of(), null));
        assertThat(r.analysis()).isNull();
        assertThat(r.error()).isNotNull();
    }

    @Test
    void returnsAnalysisFromChatCompletion() {
        configure(true);
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        AiAnalysisService s = service(h);
        h[0].expect(requestTo(Matchers.containsString("/chat/completions")))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Root cause: timeout. Fix: raise the client timeout.\"}}]}",
                        MediaType.APPLICATION_JSON));
        AiAnalysisResult r = s.analyze(new AiAnalyzeRequest("connection timeout", "ERROR", java.util.Map.of(), null));
        assertThat(r.error()).isNull();
        assertThat(r.analysis()).contains("Root cause");
    }

    @Test
    void llmErrorDegradesGracefully() {
        configure(true);
        MockRestServiceServer[] h = new MockRestServiceServer[1];
        AiAnalysisService s = service(h);
        h[0].expect(requestTo(Matchers.containsString("/chat/completions"))).andRespond(withServerError());
        AiAnalysisResult r = s.analyze(new AiAnalyzeRequest("x", "ERROR", java.util.Map.of(), null));
        assertThat(r.analysis()).isNull();
        assertThat(r.error()).isNotNull();
    }
}
```

- [ ] **Step 6: Implement the service + RestClient bean**

`src/main/java/uz/greenwhite/gateway/ai/AiClientConfig.java`:
```java
package uz.greenwhite.gateway.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AiClientConfig {

    /** Bare RestClient — the absolute LLM URI + bearer header are set per-request. */
    @Bean
    public RestClient aiRestClient() {
        return RestClient.builder().build();
    }
}
```

`src/main/java/uz/greenwhite/gateway/ai/AiAnalysisService.java`:
```java
package uz.greenwhite.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.settings.SettingsService;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Opt-in AI error analysis. Dormant until base-url + api-key + model are all set.
 * Redacts obvious secrets, builds a chat prompt, and calls a configurable
 * OpenAI-compatible /chat/completions endpoint. Any failure (unconfigured,
 * unreachable, bad key) degrades to {analysis:null, error} — never throws out.
 */
@Slf4j
@Service
public class AiAnalysisService {

    private static final String SYSTEM_PROMPT =
            "You are an SRE assistant for the 'qosid' message-gateway pipeline "
          + "(Oracle/REST ingress -> Kafka -> HTTP with CircuitBreaker+OAuth2 -> RocksDB/callback, DLQ on failure). "
          + "Given ONE error or log entry, reply with a concise root-cause hypothesis and a concrete suggested fix. "
          + "Answer in the same language as the log (Uzbek or English), as short markdown. Do not invent details.";

    private final RestClient aiRestClient;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;

    public AiAnalysisService(RestClient aiRestClient, SettingsService settings, ObjectMapper objectMapper) {
        this.aiRestClient = aiRestClient;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return !settings.get("gateway.ai.base-url", "").isBlank()
            && !settings.get("gateway.ai.api-key", "").isBlank()
            && !settings.get("gateway.ai.model", "").isBlank();
    }

    public AiAnalysisResult analyze(AiAnalyzeRequest req) {
        if (!enabled()) {
            return new AiAnalysisResult(null, "AI not configured");
        }
        String baseUrl = settings.get("gateway.ai.base-url", "").replaceAll("/+$", "");
        String apiKey = settings.getSecret("gateway.ai.api-key", "");
        String model = settings.get("gateway.ai.model", "");
        String context = AiRedactor.redact(buildContext(req));
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.2,
                "max_tokens", 800,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", context)));
        try {
            String resp = aiRestClient.post()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String content = extractContent(resp);
            return content.isBlank()
                    ? new AiAnalysisResult(null, "AI returned no content")
                    : new AiAnalysisResult(content, null);
        } catch (Exception e) {
            log.warn("AI analyze failed: {}", e.getMessage());
            return new AiAnalysisResult(null, "AI request failed: " + e.getMessage());
        }
    }

    private String buildContext(AiAnalyzeRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.level() != null) sb.append("Level: ").append(req.level()).append('\n');
        if (req.labels() != null && !req.labels().isEmpty()) sb.append("Labels: ").append(req.labels()).append('\n');
        if (req.parsed() != null && !req.parsed().isNull()) {
            sb.append("Details:\n").append(req.parsed().toPrettyString());
        } else if (req.line() != null) {
            sb.append("Log line:\n").append(req.line());
        }
        return sb.toString();
    }

    private String extractContent(String resp) {
        try {
            JsonNode choices = objectMapper.readTree(resp).path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText("");
            }
        } catch (Exception e) {
            log.warn("AI response parse failed: {}", e.getMessage());
        }
        return "";
    }
}
```

- [ ] **Step 7: Run to verify the service test passes**

Run: `mvn -P '!frontend' -Dtest=AiAnalysisServiceTest test`
Expected: PASS (3).

- [ ] **Step 8: Write the failing controller test**

`src/test/java/uz/greenwhite/gateway/admin/AiControllerTest.java`:
```java
package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uz.greenwhite.gateway.ai.AiAnalysisResult;
import uz.greenwhite.gateway.ai.AiAnalysisService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AiControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AiAnalysisService aiAnalysisService;

    @Test
    void statusUnauthenticatedIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/ai/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusReflectsEnabled() throws Exception {
        when(aiAnalysisService.enabled()).thenReturn(false);
        mvc.perform(get("/qosid/admin/api/ai/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void analyzeReturnsResult() throws Exception {
        when(aiAnalysisService.analyze(any())).thenReturn(new AiAnalysisResult("root cause X", null));
        mvc.perform(post("/qosid/admin/api/ai/analyze").with(csrf())
                        .contentType("application/json")
                        .content("{\"line\":\"boom\",\"level\":\"ERROR\",\"labels\":{},\"parsed\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").value("root cause X"));
    }
}
```

- [ ] **Step 9: Implement the controller + config DTO**

`src/main/java/uz/greenwhite/gateway/admin/dto/AiConfigRequest.java`:
```java
package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class AiConfigRequest {
    private String baseUrl;
    private String apiKey;
    private String model;
}
```

`src/main/java/uz/greenwhite/gateway/admin/AiController.java`:
```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.AiConfigRequest;
import uz.greenwhite.gateway.ai.AiAnalysisResult;
import uz.greenwhite.gateway.ai.AiAnalysisService;
import uz.greenwhite.gateway.ai.AiAnalyzeRequest;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in AI error analysis, under the /qosid/admin/api/** session-auth chain.
 * All responses are 200 (never 500) — analysis failures ride in the result's error field.
 */
@RestController
@RequestMapping("/qosid/admin/api/ai")
@RequiredArgsConstructor
public class AiController {

    private static final String MASK = "***";
    private final AiAnalysisService aiAnalysisService;
    private final SettingsService settings;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("enabled", aiAnalysisService.enabled()));
    }

    @PostMapping("/analyze")
    public ResponseEntity<AiAnalysisResult> analyze(@RequestBody AiAnalyzeRequest req) {
        return ResponseEntity.ok(aiAnalysisService.analyze(req));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        Map<String, String> out = new HashMap<>();
        out.put("baseUrl", settings.get("gateway.ai.base-url", ""));
        out.put("model", settings.get("gateway.ai.model", ""));
        out.put("apiKey", settings.get("gateway.ai.api-key", "").isBlank() ? "" : MASK);
        return ResponseEntity.ok(out);
    }

    @PutMapping("/config")
    public ResponseEntity<Void> putConfig(@RequestBody AiConfigRequest req) {
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        settings.set("gateway.ai.base-url", nullToEmpty(req.getBaseUrl()), false, admin);
        settings.set("gateway.ai.model", nullToEmpty(req.getModel()), false, admin);
        // Only persist the key when a new (non-mask) value is supplied.
        if (req.getApiKey() != null && !req.getApiKey().isBlank() && !MASK.equals(req.getApiKey())) {
            settings.set("gateway.ai.api-key", req.getApiKey(), true, admin);
        }
        return ResponseEntity.noContent().build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 10: Run the controller test + full backend suite**

Run: `mvn -P '!frontend' -Dtest=AiControllerTest test` → Expected: PASS (3).
Run: `mvn -P '!frontend' test` → Expected: all green (prior 139 + 10 new = 149), 0 failures.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/ai/ src/main/java/uz/greenwhite/gateway/admin/AiController.java src/main/java/uz/greenwhite/gateway/admin/dto/AiConfigRequest.java src/test/java/uz/greenwhite/gateway/ai/ src/test/java/uz/greenwhite/gateway/admin/AiControllerTest.java
git commit -m "feat(ai): opt-in AI error-analysis proxy (/qosid/admin/api/ai)"
```

---

### Task 2: Frontend — AI api + LogTable drawer button/panel

**Files:**
- Create: `frontend/src/api/ai.ts`
- Modify: `frontend/src/pages/errors/LogTable.tsx` (AI button + analysis panel in the drawer), `frontend/src/locales/uz.json`
- Test: `frontend/src/pages/errors/LogTable.ai.test.tsx`

**Interfaces:**
- Consumes: `api` (`api/client.ts`), `useTranslation`, `LogEntry` (`api/logs.ts`).
- Produces: `api/ai.ts` (`AiStatus`, `AiAnalysisResult`, `getAiStatus`, `analyzeError`); `LogTable` gains an AI button (shown only when `getAiStatus().enabled`) that calls `analyzeError(detail)` and renders the analysis in the drawer.

- [ ] **Step 1: Write the failing LogTable AI test**

`frontend/src/pages/errors/LogTable.ai.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { LogTable } from './LogTable';
import type { LogEntry } from '../../api/logs';
import * as aiApi from '../../api/ai';

beforeEach(() => vi.restoreAllMocks());

const entry: LogEntry = { timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: 'boom', labels: { stream: 'dlq' }, parsed: { failureReason: 'timeout' } };
const columns = [{ title: 'msg', dataIndex: 'line' }];

describe('LogTable AI', () => {
  it('shows the AI button and renders analysis when enabled', async () => {
    vi.spyOn(aiApi, 'getAiStatus').mockResolvedValue({ enabled: true });
    vi.spyOn(aiApi, 'analyzeError').mockResolvedValue({ analysis: 'Root cause: upstream timeout.', error: null });
    render(<LogTable entries={[entry]} loading={false} columns={columns as any} />);
    await userEvent.click(screen.getByText('boom'));                 // open drawer
    const btn = await screen.findByRole('button', { name: 'AI tahlil' });
    await userEvent.click(btn);
    await waitFor(() => expect(screen.getByText(/Root cause: upstream timeout/)).toBeInTheDocument());
  });

  it('hides the AI button when disabled', async () => {
    vi.spyOn(aiApi, 'getAiStatus').mockResolvedValue({ enabled: false });
    render(<LogTable entries={[entry]} loading={false} columns={columns as any} />);
    await userEvent.click(screen.getByText('boom'));
    await waitFor(() => expect(screen.getByText('timeout')).toBeInTheDocument()); // drawer open (parsed shown)
    expect(screen.queryByRole('button', { name: 'AI tahlil' })).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run (from `frontend/`): `npm test -- LogTable.ai`
Expected: FAIL — `../../api/ai` not found; no AI button.

- [ ] **Step 3: Implement the AI api wrapper**

`frontend/src/api/ai.ts`:
```ts
import { api } from './client';
import type { LogEntry } from './logs';

const BASE = '/qosid/admin/api/ai';

export interface AiStatus { enabled: boolean }
export interface AiAnalysisResult { analysis: string | null; error: string | null }

export const getAiStatus = () => api.get<AiStatus>(`${BASE}/status`).then(r => r.data);

export const analyzeError = (entry: LogEntry) =>
  api.post<AiAnalysisResult>(`${BASE}/analyze`, {
    line: entry.line, level: entry.level, labels: entry.labels, parsed: entry.parsed ?? null,
  }).then(r => r.data);
```

- [ ] **Step 4: Add the AI button + panel to the LogTable drawer**

Replace `frontend/src/pages/errors/LogTable.tsx` with (adds AI status fetch on mount, and a button + analysis panel inside the existing drawer):
```tsx
import { useEffect, useState } from 'react';
import { Table, Drawer, Tag, Alert, Button, Spin, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import type { LogEntry } from '../../api/logs';
import { getAiStatus, analyzeError, AiAnalysisResult } from '../../api/ai';

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
  const [aiEnabled, setAiEnabled] = useState(false);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiResult, setAiResult] = useState<AiAnalysisResult | null>(null);

  useEffect(() => { getAiStatus().then((s) => setAiEnabled(s.enabled)).catch(() => setAiEnabled(false)); }, []);

  const openDetail = (r: LogEntry) => { setDetail(r); setAiResult(null); };

  const runAi = () => {
    if (!detail) return;
    setAiLoading(true); setAiResult(null);
    analyzeError(detail)
      .then(setAiResult)
      .catch(() => setAiResult({ analysis: null, error: t('ai.error') }))
      .finally(() => setAiLoading(false));
  };

  return (
    <>
      {error && <Alert type="error" style={{ marginBottom: 12 }} message={error} />}
      <Table<LogEntry>
        size="small"
        loading={loading}
        rowKey={(_r, i) => String(i)}
        dataSource={entries}
        columns={columns}
        onRow={(r) => ({ onClick: () => openDetail(r), style: { cursor: 'pointer' } })}
        pagination={{ pageSize: 50 }}
      />
      <Drawer open={!!detail} title={t('errors.detail')} width={640} onClose={() => setDetail(null)}>
        {detail && (
          <>
            <p><Tag color={levelColor[detail.level] ?? 'default'}>{detail.level || '-'}</Tag> {detail.timestamp}</p>
            <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {detail.parsed ? JSON.stringify(detail.parsed, null, 2) : detail.line}
            </pre>
            {aiEnabled && (
              <div style={{ marginTop: 16 }}>
                <Button onClick={runAi} loading={aiLoading}>{t('ai.analyze')}</Button>
                {aiResult?.error && <Alert style={{ marginTop: 12 }} type="error" message={aiResult.error} />}
                {aiResult?.analysis && (
                  <div style={{ marginTop: 12, background: '#F4F6F9', padding: 12, borderRadius: 6 }}>
                    <Typography.Text strong>{t('ai.title')}</Typography.Text>
                    <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', marginTop: 8 }}>{aiResult.analysis}</pre>
                  </div>
                )}
                {aiLoading && <Spin style={{ marginLeft: 12 }} />}
              </div>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
```
Add to `frontend/src/locales/uz.json`:
```json
  "ai.analyze": "AI tahlil",
  "ai.analyzing": "Tahlil qilinmoqda...",
  "ai.title": "AI tahlili",
  "ai.error": "AI tahlil bajarilmadi",
  "ai.notConfigured": "AI sozlanmagan",
  "ai.baseUrl": "Base URL",
  "ai.apiKey": "API kalit",
  "ai.model": "Model",
  "ai.test": "Sinash",
  "ai.settingsTitle": "AI tahlil (opt-in)"
```

- [ ] **Step 5: Run the test + build**

Run (from `frontend/`): `npm test -- LogTable.ai` → Expected: PASS (2).
Run (from `frontend/`): `npm test` → Expected: all prior green + these (existing LogTable-dependent tests still pass — the AI status fetch is mocked/absent-safe: `getAiStatus` rejects → `aiEnabled=false`, no button, existing DLQ/JAR/Docker tests unaffected).
Run (from `frontend/`): `npm run build` → Expected: succeeds.

Note: existing `DlqPage`/`JarPage`/`DockerPage` tests render `LogTable`, which now calls `getAiStatus()` on mount. That network call is unmocked in those tests → the promise rejects → `.catch` sets `aiEnabled=false`; no assertion depends on the AI button, so they stay green. Confirm by running the full suite.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/ai.ts frontend/src/pages/errors/LogTable.tsx frontend/src/pages/errors/LogTable.ai.test.tsx frontend/src/locales/uz.json
git commit -m "feat(ui): AI analyze button + panel in the error drawer (opt-in)"
```

---

### Task 3: Frontend — AI config form in Settings

**Files:**
- Create: `frontend/src/pages/settings/AiConfigPage.tsx`
- Modify: `frontend/src/api/ai.ts` (add `getAiConfig`, `putAiConfig`), `frontend/src/pages/settings/SettingsPage.tsx` (add an "AI" tab)
- Test: `frontend/src/pages/settings/AiConfigPage.test.tsx`

**Interfaces:**
- Consumes: `api`, `useTranslation`; `analyzeError` from `api/ai.ts` (for the Test button).
- Produces: `api/ai.ts` gains `AiConfig` + `getAiConfig()` + `putAiConfig(body)`; `AiConfigPage` (base-url / api-key(masked) / model form + Save + Test); a new "AI" tab in `SettingsPage`.

- [ ] **Step 1: Write the failing AiConfigPage test**

`frontend/src/pages/settings/AiConfigPage.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { AiConfigPage } from './AiConfigPage';
import * as aiApi from '../../api/ai';

beforeEach(() => vi.restoreAllMocks());

describe('AiConfigPage', () => {
  it('loads config and saves without resending the masked key', async () => {
    vi.spyOn(aiApi, 'getAiConfig').mockResolvedValue({ baseUrl: 'https://llm/v1', apiKey: '***', model: 'm1' });
    const put = vi.spyOn(aiApi, 'putAiConfig').mockResolvedValue({} as any);
    render(<AiConfigPage />);
    await waitFor(() => expect(screen.getByDisplayValue('https://llm/v1')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalled());
    // masked key must NOT be resent as the literal ***
    expect(put.mock.calls[0][0].apiKey === '***').toBe(false);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run (from `frontend/`): `npm test -- AiConfigPage`
Expected: FAIL — `./AiConfigPage`, `getAiConfig` not found.

- [ ] **Step 3: Add config api wrappers**

Append to `frontend/src/api/ai.ts`:
```ts
export interface AiConfig { baseUrl: string; apiKey: string; model: string }

export const getAiConfig = () => api.get<AiConfig>(`${BASE}/config`).then(r => r.data);
export const putAiConfig = (body: Partial<AiConfig>) => api.put(`${BASE}/config`, body);
```

- [ ] **Step 4: Implement the AI config page**

`frontend/src/pages/settings/AiConfigPage.tsx`:
```tsx
import { useEffect, useState } from 'react';
import { Card, Form, Input, Button, Space, Alert, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getAiConfig, putAiConfig, analyzeError, AiConfig } from '../../api/ai';

const MASK = '***';

export function AiConfigPage() {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [loaded, setLoaded] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    getAiConfig()
      .then((c) => { form.setFieldsValue(c); setLoaded(true); })
      .catch(() => setLoaded(true));
  }, [form]);

  const onSave = async () => {
    const v = form.getFieldsValue() as AiConfig;
    setSaving(true);
    try {
      const body: Partial<AiConfig> = { baseUrl: v.baseUrl, model: v.model };
      if (v.apiKey && v.apiKey !== MASK) body.apiKey = v.apiKey; // never resend the mask
      await putAiConfig(body);
      message.success(t('settings.saved'));
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? t('ai.error'));
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => {
    setTestResult(null);
    const r = await analyzeError({
      timestamp: '', level: 'ERROR', line: 'test: connection timeout to upstream', labels: {}, parsed: null,
    });
    setTestResult(r.error ? `❌ ${r.error}` : `✅ ${r.analysis?.slice(0, 200)}`);
  };

  return (
    <Card title={t('ai.settingsTitle')} loading={!loaded}>
      <Form form={form} layout="vertical" style={{ maxWidth: 520 }}>
        <Form.Item name="baseUrl" label={t('ai.baseUrl')}>
          <Input placeholder="https://openrouter.ai/api/v1" />
        </Form.Item>
        <Form.Item name="apiKey" label={t('ai.apiKey')}>
          <Input.Password placeholder={t('common.secretPlaceholder')} />
        </Form.Item>
        <Form.Item name="model" label={t('ai.model')}>
          <Input placeholder="openai/gpt-4o-mini" />
        </Form.Item>
        <Space>
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
          <Button onClick={onTest}>{t('ai.test')}</Button>
        </Space>
      </Form>
      {testResult && <Alert style={{ marginTop: 16 }} type={testResult.startsWith('✅') ? 'success' : 'error'} message={testResult} />}
    </Card>
  );
}
```

- [ ] **Step 5: Add the AI tab to SettingsPage**

In `frontend/src/pages/settings/SettingsPage.tsx`, import `AiConfigPage` and add a fourth tab after `advanced`:
```tsx
import { AiConfigPage } from './AiConfigPage';
```
```tsx
        { key: 'ai', label: t('ai.settingsTitle'), children: <AiConfigPage /> },
```
(Add it to the existing `items` array of the `Tabs`.)

- [ ] **Step 6: Run the test + full suite + build**

Run (from `frontend/`): `npm test -- AiConfigPage` → Expected: PASS (1).
Run (from `frontend/`): `npm test` → Expected: all green.
Run (from `frontend/`): `npm run build` → Expected: succeeds.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/settings/AiConfigPage.tsx frontend/src/pages/settings/AiConfigPage.test.tsx frontend/src/api/ai.ts frontend/src/pages/settings/SettingsPage.tsx
git commit -m "feat(ui): AI config form (base-url/key/model + test) in Settings"
```

---

### Task 4: E2E + full build + opt-in smoke

**Files:**
- Create: `frontend/e2e/ai.spec.ts`

**Interfaces:**
- Consumes: the built SPA (vite preview) with the admin API mocked via `page.route`, like the other specs.
- Produces: an E2E proving login → open a DLQ error → (AI enabled) click "AI tahlil" → analysis renders.

- [ ] **Step 1: Write the failing E2E**

`frontend/e2e/ai.spec.ts`:
```ts
import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/ai/status', (r) => r.fulfill({ status: 200, body: JSON.stringify({ enabled: true }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/ai/analyze', (r) => r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({ analysis: 'Root cause: upstream timeout. Fix: raise the client timeout.', error: null }) }));
  await page.route('**/qosid/admin/api/logs?*', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: '{"failureReason":"timeout"}', labels: { stream: 'dlq' }, parsed: { failureReason: 'timeout' } }] }) }));
});

test('login, open a DLQ error, run AI analysis', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await expect(page.getByText('Sozlamalar')).toBeVisible();
  await page.goto('/errors/dlq');
  await page.getByText('timeout').first().click();      // open the row detail drawer
  await page.getByRole('button', { name: 'AI tahlil' }).click();
  await expect(page.getByText(/Root cause: upstream timeout/)).toBeVisible();
});
```

- [ ] **Step 2: Run the E2E**

Run (from `frontend/`): `npm run e2e`
Expected: 4 passed (settings + errors + statistics + ai). If chromium missing: `npx playwright install chromium` then retry.

- [ ] **Step 3: Full single-JAR build + backend regression**

Run: `mvn package -DskipTests` (frontend ON) → Expected: BUILD SUCCESS; `jar tf target/qosid-flow-1.0.0-SNAPSHOT.jar | grep static/index.html` present.
Run: `mvn -P '!frontend' test` → Expected: 149 backend tests green.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/ai.spec.ts
git commit -m "test(ui): E2E for opt-in AI error analysis"
```

---

## Phase-end verification (controller runs after all tasks)

1. `mvn -P '!frontend' test` — 149 backend tests green.
2. `cd frontend && npm test` — all frontend tests green.
3. `cd frontend && npm run e2e` — Playwright green (4 specs).
4. `mvn package -DskipTests` — single JAR with `static/index.html`.
5. **Opt-in smoke:** boot the JAR with NO AI config → `GET /qosid/admin/api/ai/status` (after login) → `{enabled:false}`; open an error in the UI → NO "AI tahlil" button (confirms zero-cost dormant state); `POST /ai/analyze` → `{analysis:null, error:"AI not configured"}` HTTP 200. (Optionally, if a real key is available, set `gateway.ai.*` via the Settings AI tab and confirm a live analysis.)

## Self-Review notes (author)

- **Spec coverage:** service/redaction/prompt/OpenAI-compat call/never-500/disabled-gate + controller (status/analyze/config) → Task 1; AI button/panel in the shared drawer (all 3 sources) → Task 2; AI config form + Test → Task 3; E2E/opt-in smoke → Task 4. All spec §3–§8 map to a task.
- **Opt-in invariant:** `enabled()` requires all three config keys non-blank; `/ai/status` gates the UI button; `analyze` returns the not-configured error without any external call. No `@Value` default endpoint exists (empty = off).
- **Secret handling:** `api-key` stored AES (`set(..., true, ...)`), masked `***` on GET, and the config form never resends the mask (Task 3) — mirrors `SourceAuthController`.
- **never-500:** service catches all exceptions → `{null, error}`; controller always `ResponseEntity.ok`.
- **No new SPA route:** the AI button lives inside the existing D2 `LogTable` drawer, so no `SpaForwardController` change is needed (unlike D2/D3 which added routes). The Settings AI tab is under the existing `/settings` route.
- **Type consistency:** backend `AiAnalysisResult(analysis, error)` ↔ frontend `AiAnalysisResult {analysis, error}`; `AiAnalyzeRequest(line, level, labels, parsed)` matches the `analyzeError(entry)` body; `AiConfig {baseUrl, apiKey, model}` matches the `/config` GET/PUT shapes.
- **Existing-test safety:** `LogTable` now fetches `/ai/status` on mount; in DlqPage/JarPage/DockerPage tests that call is unmocked → rejects → `aiEnabled=false`; nothing asserts the AI button there, so they stay green (called out in Task 2 Step 5).
