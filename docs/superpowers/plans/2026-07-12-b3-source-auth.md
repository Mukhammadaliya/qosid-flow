# B3: Source Channel Outbound Auth — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Biruni source channel (pull + save) auth configurable — Basic | API-KEY | OAuth2 — managed via the B1 settings UI, without touching request-forwarding.

**Architecture:** A single `SourceAuthService` reads the configured `gateway.source.auth.type` per call and returns the auth header(s) to apply; `BiruniClient` applies them on pull + save. OAuth2 tokens are cached in Redis under an isolated `source:oauth2:token` key (never touching the per-request `oauth2:token:*` cache), fetched via the existing `StandardOAuth2Client`. Default type `basic` with a yaml fallback keeps prod working.

**Tech Stack:** Java 21, Spring Boot 3.3.0, StringRedisTemplate, StandardOAuth2Client (existing), SettingsService (B1), JUnit 5 + Mockito + MockMvc, Maven.

## Global Constraints

- Branch: `debug`. Never push `master`.
- Base package `uz.greenwhite.gateway`. Lombok idioms; explicit constructor where `@Qualifier` is needed.
- **Request-forwarding (`HttpRequestService` per-request `oauth2_provider` → target API) is NOT touched.** Pipeline core untouched.
- **bmb / prod must keep working:** default `gateway.source.auth.type=basic`; basic creds fall back to yaml `GatewayProperties.getUsername()/getPassword()` when the settings DB has none.
- **Source OAuth2 token cache MUST be isolated** from the per-request cache: Redis key `source:oauth2:token`, NOT under `oauth2:token:*`.
- Source OAuth2 cache TTL = `min(configured gateway.source.auth.oauth2.cache-ttl-seconds, token.expiresIn/1000 − 15s margin)`; blank config → `expiresIn/1000 − 15s`.
- Secrets (`basic.password`, `apikey.value`, `oauth2.client-secret`) stored AES-encrypted via `SettingsService.set(key, value, true, changedBy)` and read via `getSecret`.
- **Maven NOT on PATH.** Test (PowerShell): `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test` (add `-Dtest=ClassName` for focus). Full suite green after each task. Baseline: 75 tests.
- Every task TDD: failing test → RED → implement → GREEN → commit.

## Config keys (settings DB, B1)

| Key | Default | Secret |
|---|---|---|
| `gateway.source.auth.type` | `basic` | — |
| `gateway.source.auth.basic.username` | (migrated from SOURCE_USERNAME) | — |
| `gateway.source.auth.basic.password` | (migrated from SOURCE_PASSWORD) | AES |
| `gateway.source.auth.apikey.header` | `X-Api-Key` | — |
| `gateway.source.auth.apikey.value` | — | AES |
| `gateway.source.auth.oauth2.token-url` | — | — |
| `gateway.source.auth.oauth2.client-id` | — | — |
| `gateway.source.auth.oauth2.client-secret` | — | AES |
| `gateway.source.auth.oauth2.scope` | (blank) | — |
| `gateway.source.auth.oauth2.cache-ttl-seconds` | (blank) | — |

## Fayl xaritasi

- Create: `source/SourceTokenService.java` (OAuth2 token cache, isolated Redis key)
- Create: `source/SourceAuthService.java` (type switch → auth headers)
- Modify: `oracle/BiruniClient.java` (apply source auth on pull + save)
- Modify: `settings/SettingsMigrationRunner.java` (migrate basic creds)
- Create: `admin/SourceAuthController.java`, `admin/dto/SourceAuthRequest.java`
- Modify: `ai-context/*`, `VERSION.md`

---

### Task 1: SourceTokenService (isolated OAuth2 source-token cache)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/source/SourceTokenService.java`
- Test: `src/test/java/uz/greenwhite/gateway/source/SourceTokenServiceTest.java`

**Interfaces:**
- Consumes: `SettingsService.get(key,default)` + `getSecret(key,default)` (B1), `StandardOAuth2Client.getAccessToken(ProviderProperties):Token` (existing), `StringRedisTemplate` (existing bean), `ObjectMapper` (bean).
- Produces: `SourceTokenService.getBearerToken():String` — returns a cached-or-freshly-minted access token string for the source channel. Cache key `source:oauth2:token`.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uz.greenwhite.gateway.oauth2.client.StandardOAuth2Client;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceTokenServiceTest {

    @Mock SettingsService settings;
    @Mock StandardOAuth2Client standardClient;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private final ObjectMapper om = new ObjectMapper();

    private SourceTokenService service() {
        return new SourceTokenService(settings, standardClient, redis, om);
    }

    private void stubOAuthSettings() {
        when(settings.get("gateway.source.auth.oauth2.token-url", "")).thenReturn("http://idp/token");
        when(settings.get("gateway.source.auth.oauth2.client-id", "")).thenReturn("cid");
        when(settings.getSecret("gateway.source.auth.oauth2.client-secret", "")).thenReturn("secret");
        when(settings.get("gateway.source.auth.oauth2.scope", "")).thenReturn("");
        when(settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", "")).thenReturn("");
    }

    @Test
    void cacheMissFetchesAndCachesUnderIsolatedKey() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("source:oauth2:token")).thenReturn(null); // miss
        stubOAuthSettings();
        when(standardClient.getAccessToken(any(ProviderProperties.class)))
                .thenReturn(new Token("ACCESS", 3600_000, "Bearer", null));

        String token = service().getBearerToken();

        assertEquals("ACCESS", token);
        verify(standardClient).getAccessToken(any(ProviderProperties.class));
        // cached under the ISOLATED key (never oauth2:token:*)
        verify(valueOps).set(eq("source:oauth2:token"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void cacheHitReturnsWithoutFetch() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        Token cached = new Token("CACHED", 3600_000, "Bearer", null);
        when(valueOps.get("source:oauth2:token")).thenReturn(om.writeValueAsString(cached));

        String token = service().getBearerToken();

        assertEquals("CACHED", token);
        verify(standardClient, never()).getAccessToken(any());
    }

    @Test
    void configuredTtlCapsAtTokenLifetime() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("source:oauth2:token")).thenReturn(null);
        when(settings.get("gateway.source.auth.oauth2.token-url", "")).thenReturn("http://idp/token");
        when(settings.get("gateway.source.auth.oauth2.client-id", "")).thenReturn("cid");
        when(settings.getSecret("gateway.source.auth.oauth2.client-secret", "")).thenReturn("secret");
        when(settings.get("gateway.source.auth.oauth2.scope", "")).thenReturn("");
        when(settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", "")).thenReturn("99999"); // > token life
        when(standardClient.getAccessToken(any(ProviderProperties.class)))
                .thenReturn(new Token("A", 60_000, "Bearer", null)); // 60s token

        service().getBearerToken();

        // TTL capped at 60 - 15 = 45s, not 99999
        verify(valueOps).set(eq("source:oauth2:token"), anyString(), eq(45L), eq(TimeUnit.SECONDS));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SourceTokenServiceTest`
Expected: FAIL — `SourceTokenService` does not exist.

- [ ] **Step 3: Implement SourceTokenService**

```java
package uz.greenwhite.gateway.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.oauth2.client.StandardOAuth2Client;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.concurrent.TimeUnit;

/**
 * OAuth2 client_credentials token cache for the SOURCE channel (Biruni pull/save).
 * Deliberately isolated from the per-request provider cache ({@code oauth2:token:*})
 * by using the reserved key {@code source:oauth2:token} — a source-channel token
 * must never collide with, or be evicted by, a per-request forwarding provider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceTokenService {

    private static final String CACHE_KEY = "source:oauth2:token";
    private static final long MARGIN_SECONDS = 15;

    private final SettingsService settings;
    private final StandardOAuth2Client standardClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** @return a valid access token for the source channel (cached or freshly minted). */
    public String getBearerToken() {
        String cached = readCache();
        if (cached != null) {
            return cached;
        }
        Token token = standardClient.getAccessToken(buildProps());
        cache(token);
        return token.accessToken();
    }

    private String readCache() {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            if (json == null) return null;
            Token token = objectMapper.readValue(json, Token.class);
            return token.isExpired() ? null : token.accessToken();
        } catch (Exception e) {
            log.warn("Source token cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private void cache(Token token) {
        try {
            long tokenTtl = token.expiresIn() / 1000 - MARGIN_SECONDS;
            long ttl = tokenTtl;
            String configured = settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", "");
            if (configured != null && !configured.isBlank()) {
                try {
                    long cfg = Long.parseLong(configured.trim());
                    ttl = Math.min(cfg, tokenTtl);
                } catch (NumberFormatException ignored) { /* keep tokenTtl */ }
            }
            if (ttl <= 0) ttl = 1;
            redisTemplate.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(token), ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Source token cache write failed: {}", e.getMessage());
        }
    }

    private ProviderProperties buildProps() {
        return ProviderProperties.builder()
                .type("standard")
                .tokenUrl(settings.get("gateway.source.auth.oauth2.token-url", ""))
                .clientId(settings.get("gateway.source.auth.oauth2.client-id", ""))
                .clientSecret(settings.getSecret("gateway.source.auth.oauth2.client-secret", ""))
                .scope(settings.get("gateway.source.auth.oauth2.scope", ""))
                .build();
    }
}
```

- [ ] **Step 4: Run test + full suite + commit**

Run: `... mvn.cmd test` → 78 tests, 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/source/SourceTokenService.java src/test/java/uz/greenwhite/gateway/source/SourceTokenServiceTest.java
git commit -m "feat(source-auth): isolated OAuth2 source-token cache (source:oauth2:token)"
```

---

### Task 2: SourceAuthService (basic + apikey + oauth2 + yaml fallback)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/source/SourceAuthService.java`
- Test: `src/test/java/uz/greenwhite/gateway/source/SourceAuthServiceTest.java`

**Interfaces:**
- Consumes: `SettingsService.get/getSecret` (B1), `SourceTokenService.getBearerToken()` (Task 1), `GatewayProperties.getUsername()/getPassword()` (yaml fallback), `AuthUtil.generateBasicAuth(user,pass)` (existing).
- Produces: `SourceAuthService.authHeaders():Map<String,String>` — the header(s) BiruniClient applies to pull + save. Type read per call from `gateway.source.auth.type` (default `basic`).

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceAuthServiceTest {

    @Mock SettingsService settings;
    @Mock SourceTokenService sourceTokenService;
    @Mock GatewayProperties gatewayProperties;

    private SourceAuthService service() {
        return new SourceAuthService(settings, sourceTokenService, gatewayProperties);
    }

    @Test
    void basicFromSettings() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("basic");
        when(settings.get("gateway.source.auth.basic.username", "")).thenReturn("u");
        when(settings.getSecret("gateway.source.auth.basic.password", "")).thenReturn("p");

        Map<String, String> h = service().authHeaders();
        assertTrue(h.get("Authorization").startsWith("Basic "));
    }

    @Test
    void basicFallsBackToYamlWhenSettingsBlank() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("basic");
        when(settings.get("gateway.source.auth.basic.username", "")).thenReturn("");
        when(settings.getSecret("gateway.source.auth.basic.password", "")).thenReturn("");
        when(gatewayProperties.getUsername()).thenReturn("yamlUser");
        when(gatewayProperties.getPassword()).thenReturn("yamlPass");

        Map<String, String> h = service().authHeaders();
        assertTrue(h.get("Authorization").startsWith("Basic "));
    }

    @Test
    void apikeyUsesConfiguredHeaderAndValue() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("apikey");
        when(settings.get("gateway.source.auth.apikey.header", "X-Api-Key")).thenReturn("X-Api-Key");
        when(settings.getSecret("gateway.source.auth.apikey.value", "")).thenReturn("k123");

        Map<String, String> h = service().authHeaders();
        assertEquals("k123", h.get("X-Api-Key"));
        assertFalse(h.containsKey("Authorization"));
    }

    @Test
    void oauth2UsesBearerFromTokenService() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("oauth2");
        when(sourceTokenService.getBearerToken()).thenReturn("TKN");

        Map<String, String> h = service().authHeaders();
        assertEquals("Bearer TKN", h.get("Authorization"));
    }

    @Test
    void unknownTypeFallsBackToBasic() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("weird");
        when(settings.get("gateway.source.auth.basic.username", "")).thenReturn("");
        when(settings.getSecret("gateway.source.auth.basic.password", "")).thenReturn("");
        when(gatewayProperties.getUsername()).thenReturn("u");
        when(gatewayProperties.getPassword()).thenReturn("p");

        Map<String, String> h = service().authHeaders();
        assertTrue(h.get("Authorization").startsWith("Basic "));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SourceAuthServiceTest`
Expected: FAIL — `SourceAuthService` does not exist.

- [ ] **Step 3: Implement SourceAuthService**

```java
package uz.greenwhite.gateway.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.util.AuthUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the outbound auth header(s) for the SOURCE channel (Biruni pull/save).
 * The type ({@code basic}|{@code apikey}|{@code oauth2}) is read per call from
 * settings so a UI change takes effect immediately. Basic falls back to the yaml
 * GatewayProperties when the settings DB has no credentials (dev / pre-migration).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAuthService {

    private final SettingsService settings;
    private final SourceTokenService sourceTokenService;
    private final GatewayProperties gatewayProperties;

    public Map<String, String> authHeaders() {
        String type = settings.get("gateway.source.auth.type", "basic");
        Map<String, String> headers = new LinkedHashMap<>();
        switch (type == null ? "basic" : type.toLowerCase()) {
            case "apikey" -> {
                String header = settings.get("gateway.source.auth.apikey.header", "X-Api-Key");
                String value = settings.getSecret("gateway.source.auth.apikey.value", "");
                headers.put(header, value);
            }
            case "oauth2" -> headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + sourceTokenService.getBearerToken());
            default -> headers.put(HttpHeaders.AUTHORIZATION, basicHeader());
        }
        return headers;
    }

    private String basicHeader() {
        String user = settings.get("gateway.source.auth.basic.username", "");
        String pass = settings.getSecret("gateway.source.auth.basic.password", "");
        if (user == null || user.isBlank()) {
            // Fallback to yaml GatewayProperties (dev / pre-migration).
            user = gatewayProperties.getUsername();
            pass = gatewayProperties.getPassword();
        }
        return AuthUtil.generateBasicAuth(user, pass);
    }
}
```

- [ ] **Step 4: Run test + full suite + commit**

Run: `... mvn.cmd test` → 83 tests, 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/source/SourceAuthService.java src/test/java/uz/greenwhite/gateway/source/SourceAuthServiceTest.java
git commit -m "feat(source-auth): SourceAuthService (basic/apikey/oauth2) with yaml fallback"
```

---

### Task 3: BiruniClient applies source auth + basic-creds migration

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java`
- Modify: `src/main/java/uz/greenwhite/gateway/settings/SettingsMigrationRunner.java`
- Test: `src/test/java/uz/greenwhite/gateway/oracle/BiruniClientAuthTest.java`

**Interfaces:**
- Consumes: `SourceAuthService.authHeaders()` (Task 2).
- Produces: `BiruniClient` applies the source-auth headers on BOTH pull and save (replacing the hardcoded `AuthUtil.generateBasicAuth`). Migration seeds `gateway.source.auth.basic.username`/`.password` from `SOURCE_USERNAME`/`SOURCE_PASSWORD`.

- [ ] **Step 1: Write the failing test (verifies BiruniClient asks SourceAuthService for headers)**

```java
package uz.greenwhite.gateway.oracle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BiruniClientAuthTest {

    @Mock GatewayProperties properties;
    @Mock TenantMappingService tenantMappingService;
    @Mock SourceAuthService sourceAuthService;

    @Test
    void pullAsksSourceAuthServiceForHeaders() {
        when(properties.getBaseUrl()).thenReturn("http://localhost:0"); // unreachable → pull fails after auth resolved
        when(properties.getConnectionTimeout()).thenReturn(1);
        when(properties.getRequestPullUri()).thenReturn("/pull");
        when(sourceAuthService.authHeaders()).thenReturn(Map.of("Authorization", "Basic xxx"));

        BiruniClient client = new BiruniClient(properties, tenantMappingService, sourceAuthService);
        try { client.pullRequests(); } catch (Exception ignored) { /* connection fails, that's fine */ }

        // The point: auth headers were requested from the service, not hardcoded.
        verify(sourceAuthService).authHeaders();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=BiruniClientAuthTest`
Expected: FAIL — `BiruniClient` constructor has no `SourceAuthService` param.

- [ ] **Step 3: Wire BiruniClient**

In `oracle/BiruniClient.java`: add a `SourceAuthService` field + constructor param (BiruniClient currently has `(GatewayProperties, TenantMappingService)` — add `SourceAuthService` as the 3rd param, assign it). Add import.

In `pullRequests()`, replace the single `.header(HttpHeaders.AUTHORIZATION, AuthUtil.generateBasicAuth(...))` with applying the resolved map:
```java
Map<String, String> authHeaders = sourceAuthService.authHeaders();
List<JsonNode> raw = restClient.get()
        .uri(properties.getRequestPullUri())
        .headers(h -> authHeaders.forEach(h::add))
        .retrieve()
        .toEntity(new ParameterizedTypeReference<List<JsonNode>>() {})
        .getBody();
```
(Keep the rest of pull — the `raw.stream().map(tenantMappingService::toInternal)` mapping from C-faza — unchanged.)

In `saveResponse(...)`, replace the `.header(HttpHeaders.AUTHORIZATION, AuthUtil.generateBasicAuth(...))` similarly:
```java
Map<String, String> authHeaders = sourceAuthService.authHeaders();
var response = restClient.post()
        .uri(properties.getResponseSaveUri())
        .headers(h -> authHeaders.forEach(h::add))
        .contentType(MediaType.APPLICATION_JSON)
        .body(List.of(body))
        .exchange(...);  // unchanged body (toExternal map from C-faza)
```
Remove the now-unused `AuthUtil` import from BiruniClient only if no longer referenced there (it's still used inside SourceAuthService — that's a different file). Add `import java.util.Map;` if missing.

- [ ] **Step 4: Add basic-creds migration**

`MIGRATABLE_KEYS` maps settings-key → yaml-property and migrates values via `settingsService.set(key, value, false, "migration")` — i.e. NON-secret only. So:

1. Add ONLY the non-secret username to `MIGRATABLE_KEYS`:
```java
        put("gateway.source.auth.basic.username", "gateway.source.username");
```
(Do NOT add `gateway.source.auth.type` — it stays `basic` via the code default; there is no yaml source for it. Do NOT add the password here — it is a secret and the generic loop can't set `secret=true`.)

2. In `SettingsMigrationRunner.run()`, AFTER the generic loop, migrate the basic password as a SECRET. Guard it so a blank encryption key (which makes `set(..., true, ...)` throw) degrades to the yaml fallback rather than failing startup:
```java
String sourcePassword = environment.getProperty("gateway.source.password");
if (sourcePassword != null && !sourcePassword.isBlank()) {
    try {
        settingsService.set("gateway.source.auth.basic.password", sourcePassword, true, "migration");
    } catch (Exception e) {
        log.warn("Skipped source basic-password migration (encryption disabled?): {} — yaml fallback applies", e.getMessage());
    }
}
```
(`SettingsMigrationRunner` is `@Slf4j`? add it if the class lacks a logger. `environment` is already an injected field used by the generic loop.)

- [ ] **Step 5: Run tests + full suite + commit**

Run: `... mvn.cmd test` → 84 tests, 0 failures. Existing tests that build `BiruniClient` (if any) need the new constructor arg — grep `new BiruniClient(` in src/test and update; BiruniClient is normally Spring-wired so there may be none.

```bash
git add -A
git commit -m "feat(source-auth): BiruniClient applies configurable source auth on pull+save; migrate basic creds"
```

---

### Task 4: Admin API — source-auth GET/PUT/test

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/admin/SourceAuthController.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/dto/SourceAuthRequest.java`
- Test: `src/test/java/uz/greenwhite/gateway/admin/SourceAuthApiTest.java`

**Interfaces:**
- Consumes: `SettingsService.get/getSecret/set` (B1), `SourceAuthService.authHeaders()` (Task 2), `GatewayProperties` (for the test-connection URL). Lives under `/qosid/admin/api/**` (B1 admin chain → session required).
- Produces: `GET /qosid/admin/api/source-auth` (masked), `PUT /qosid/admin/api/source-auth` (validated), `POST /qosid/admin/api/source-auth/test`.

- [ ] **Step 1: Write the failing integration test**

```java
package uz.greenwhite.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:source_auth_test;DB_CLOSE_DELAY=-1",
        "gateway.rocksdb.path=./target/test-data/rocksdb-source-auth",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class SourceAuthApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private MockHttpSession login() throws Exception {
        var res = mvc.perform(post("/qosid/admin/api/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    @Test
    void unauthenticatedGetIs401() throws Exception {
        mvc.perform(get("/qosid/admin/api/source-auth")).andExpect(status().isUnauthorized());
    }

    @Test
    void putThenGetMasksSecret() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "type", "apikey", "apikeyHeader", "X-Api-Key", "apikeyValue", "secret-k"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/qosid/admin/api/source-auth").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("apikey"))
                .andExpect(jsonPath("$.apikeyValue").value("***")); // masked
    }

    @Test
    void putOauth2MissingTokenUrlIs400() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/source-auth").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("type", "oauth2", "oauth2ClientId", "cid"))))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SourceAuthApiTest`
Expected: FAIL — no `SourceAuthController`.

- [ ] **Step 3: Create the DTO**

```java
package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class SourceAuthRequest {
    private String type;               // basic | apikey | oauth2
    private String basicUsername;
    private String basicPassword;      // secret
    private String apikeyHeader;
    private String apikeyValue;        // secret
    private String oauth2TokenUrl;
    private String oauth2ClientId;
    private String oauth2ClientSecret; // secret
    private String oauth2Scope;
    private String oauth2CacheTtlSeconds;
}
```

- [ ] **Step 4: Create SourceAuthController**

```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.admin.dto.SourceAuthRequest;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin management of the source-channel outbound auth (Biruni pull/save).
 * Secrets are stored AES-encrypted and masked on read. The test endpoint issues
 * one light authenticated request to the source pull URI to validate the config.
 */
@Slf4j
@RestController
@RequestMapping("/qosid/admin/api/source-auth")
@RequiredArgsConstructor
public class SourceAuthController {

    private static final String MASK = "***";
    private final SettingsService settings;
    private final SourceAuthService sourceAuthService;
    private final GatewayProperties gatewayProperties;

    @GetMapping
    public ResponseEntity<Map<String, String>> get() {
        Map<String, String> out = new HashMap<>();
        out.put("type", settings.get("gateway.source.auth.type", "basic"));
        out.put("basicUsername", settings.get("gateway.source.auth.basic.username", ""));
        out.put("basicPassword", maskIfPresent("gateway.source.auth.basic.password"));
        out.put("apikeyHeader", settings.get("gateway.source.auth.apikey.header", "X-Api-Key"));
        out.put("apikeyValue", maskIfPresent("gateway.source.auth.apikey.value"));
        out.put("oauth2TokenUrl", settings.get("gateway.source.auth.oauth2.token-url", ""));
        out.put("oauth2ClientId", settings.get("gateway.source.auth.oauth2.client-id", ""));
        out.put("oauth2ClientSecret", maskIfPresent("gateway.source.auth.oauth2.client-secret"));
        out.put("oauth2Scope", settings.get("gateway.source.auth.oauth2.scope", ""));
        out.put("oauth2CacheTtlSeconds", settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", ""));
        return ResponseEntity.ok(out);
    }

    @PutMapping
    public ResponseEntity<Void> put(@RequestBody SourceAuthRequest req) {
        String type = req.getType() == null ? "" : req.getType().toLowerCase();
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        switch (type) {
            case "basic" -> {
                requireNonBlank(req.getBasicUsername(), "basicUsername");
                set("gateway.source.auth.basic.username", req.getBasicUsername(), false, admin);
                if (req.getBasicPassword() != null && !req.getBasicPassword().isBlank()) {
                    set("gateway.source.auth.basic.password", req.getBasicPassword(), true, admin);
                }
            }
            case "apikey" -> {
                requireNonBlank(req.getApikeyHeader(), "apikeyHeader");
                requireNonBlank(req.getApikeyValue(), "apikeyValue");
                set("gateway.source.auth.apikey.header", req.getApikeyHeader(), false, admin);
                set("gateway.source.auth.apikey.value", req.getApikeyValue(), true, admin);
            }
            case "oauth2" -> {
                requireNonBlank(req.getOauth2TokenUrl(), "oauth2TokenUrl");
                requireNonBlank(req.getOauth2ClientId(), "oauth2ClientId");
                requireNonBlank(req.getOauth2ClientSecret(), "oauth2ClientSecret");
                set("gateway.source.auth.oauth2.token-url", req.getOauth2TokenUrl(), false, admin);
                set("gateway.source.auth.oauth2.client-id", req.getOauth2ClientId(), false, admin);
                set("gateway.source.auth.oauth2.client-secret", req.getOauth2ClientSecret(), true, admin);
                set("gateway.source.auth.oauth2.scope", nullToEmpty(req.getOauth2Scope()), false, admin);
                set("gateway.source.auth.oauth2.cache-ttl-seconds", nullToEmpty(req.getOauth2CacheTtlSeconds()), false, admin);
            }
            default -> throw new IllegalArgumentException("type must be basic|apikey|oauth2");
        }
        set("gateway.source.auth.type", type, false, admin);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> out = new HashMap<>();
        try {
            Map<String, String> authHeaders = sourceAuthService.authHeaders();
            int status = RestClient.create().get()
                    .uri(gatewayProperties.getBaseUrl() + gatewayProperties.getRequestPullUri())
                    .headers(h -> authHeaders.forEach(h::add))
                    .retrieve()
                    .onStatus(s -> true, (rq, rs) -> {}) // don't throw on non-2xx
                    .toBodilessEntity()
                    .getStatusCode().value();
            out.put("ok", status >= 200 && status < 400);
            out.put("httpStatus", status);
            out.put("message", "Reached source with configured auth");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    private String maskIfPresent(String key) {
        return settings.get(key, "").isBlank() ? "" : MASK;
    }

    private void set(String key, String value, boolean secret, String admin) {
        settings.set(key, value, secret, admin);
    }

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 5: Run test + full suite + commit**

Run: `... mvn.cmd test` → 87 tests, 0 failures. (`IllegalArgumentException` → 400 via the B1 handler.)

```bash
git add src/main/java/uz/greenwhite/gateway/admin/SourceAuthController.java src/main/java/uz/greenwhite/gateway/admin/dto/SourceAuthRequest.java src/test/java/uz/greenwhite/gateway/admin/SourceAuthApiTest.java
git commit -m "feat(source-auth): admin API to view/set/test source channel auth"
```

---

### Task 5: Docs + version 1.6.0 + smoke

**Files:**
- Modify: `ai-context/CONFIGURATION.md`, `ai-context/ARCHITECTURE.md`, `ai-context/SAFETY-RULES.md`, `CLAUDE.md`, `VERSION.md`

- [ ] **Step 1: CONFIGURATION.md**

Add a `## gateway.source.auth.* — Source Channel Outbound Auth (B3)` section documenting all 10 keys (type default basic; basic.username/password; apikey.header/value; oauth2.token-url/client-id/client-secret/scope/cache-ttl-seconds), which are secret, and that they're UI-managed via `/qosid/admin/api/source-auth`. Note the basic yaml fallback (SOURCE_USERNAME/PASSWORD) and that the OAuth2 source token cache is isolated (`source:oauth2:token`).

- [ ] **Step 2: ARCHITECTURE.md**

Add a "Source channel auth (B3)" note under the E1 (pull) / E5 (save) descriptions: BiruniClient resolves auth via `SourceAuthService` (basic|apikey|oauth2), OAuth2 tokens cached in `source:oauth2:token` (separate from per-request `oauth2:token:*`). Add `source/` to the component map.

- [ ] **Step 3: SAFETY-RULES.md rule 31**

```markdown
### 31. Never cache the source OAuth2 token under the per-request namespace

`SourceTokenService` caches the source-channel token under `source:oauth2:token`,
deliberately isolated from the per-request forwarding cache (`oauth2:token:<provider>`,
`OAuth2TokenRedisCache`). Using the shared namespace would let a per-request provider
eviction wipe the source token (stalling all pulls) or a "source"-named provider
collide with it. Keep the two caches separate; keep the source cache TTL capped at
the token's own `expires_in` so an expired token is never served on the pull/save channel.
```

- [ ] **Step 4: CLAUDE.md**

Add the `/qosid/admin/api/source-auth` row to the admin API description; note B3 source-channel configurable auth in the architecture summary.

- [ ] **Step 5: Version bump + ghost grep**

`VERSION.md` → `1.6.0`. Run `grep -rn "gateway.source.auth" src/ ai-context/` → confirm consistent key naming across code and docs.

- [ ] **Step 6: Full build + test + smoke**

Run:
```
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests -q
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test
```
Expected: BUILD SUCCESS, 87 tests, 0 failures.

Smoke (Docker RedPanda+Redis up): run the jar with `SETTINGS_ENCRYPTION_KEY=smoke-key-0123456789 POLLING_ENABLED=false SETTINGS_DB_PATH=./target/smoke-b3 ROCKSDB_PATH=./target/smoke-b3-rocks`, wait for "Started", then via the CSRF cookie-echo flow (login admin/admin):
- `GET /qosid/admin/api/source-auth` → 200, `type: basic`.
- `PUT /qosid/admin/api/source-auth` `{"type":"apikey","apikeyHeader":"X-Api-Key","apikeyValue":"smoke-key"}` → 204.
- `GET /qosid/admin/api/source-auth` → `type: apikey`, `apikeyValue: ***`.
- `POST /qosid/admin/api/source-auth/test` → 200 with `{ok, httpStatus, message}` (may be ok:false if the source is unreachable in dev — the point is it returns a structured result, not a 500).
- `GET /qosid/api/v1/requests/0:1/status` (no auth) → 404 (pipeline open).
Capture outputs. Stop the app.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: source channel auth (SAFETY rule 31, config); bump version to 1.6.0"
```

---

## Yakuniy eslatmalar (executor uchun)

- Default `type=basic` + yaml fallback → prod Biruni pull/save o'zgarmaydi; regressiya + smoke buni tasdiqlaydi.
- Source OAuth2 kesh kaliti `source:oauth2:token` — `oauth2:token:*` bilan HECH QACHON aralashmaydi (SAFETY rule 31, izolyatsiya testi).
- Request-forwarding (`HttpRequestService`) va pipeline yadrosi TEGILMAYDI.
- Release (staging/master) — plan tugagach ALOHIDA manual qadam.
