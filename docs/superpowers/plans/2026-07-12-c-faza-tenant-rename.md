# C-faza: Tenant Rename + Boundary Translation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `companyId`/`filialId` → `tenantId`/`extraTenantId` internally, and route all Biruni/OAuth boundary field names through a UI-configurable `TenantMappingService`, so the main app (bmb) is untouched while the internal core is clean.

**Architecture:** Anti-corruption layer. Internal core (models, Redis/Kafka keys, metrics, DLQ, status API) becomes 100% `tenant`. At the Biruni pull ingress, `TenantMappingService.toInternal(JsonNode)` reads the configured field name (default `company_id`) into `tenantId`. At the Biruni save + OAuth fetch egress, `toExternal(tenantId)` emits the configured key back. REST `/queue` tenant comes from the auth principal with a `default-rest-tenant` fallback. Plus B2-folded typed validation + a mapping-test endpoint.

**Tech Stack:** Java 21, Spring Boot 3.3.0, Jackson, JdbcTemplate/SettingsService (B1), JUnit 5 + Mockito + MockMvc, Maven.

## Global Constraints

- Branch: `debug`. Never push `master`.
- Base package `uz.greenwhite.gateway`. Lombok `@Data/@Builder/@NoArgsConstructor/@AllArgsConstructor`, `@Slf4j`, explicit constructors where `@Qualifier` is needed (repo convention).
- **The rename value does NOT change:** `company_id=100` → `tenantId=100`. NO Redis/Kafka data migration. `compositeId` format stays `tenantId:requestId`.
- `extraTenantId` does NOT enter idempotency/partition keys.
- **bmb must stay working:** default field names `company_id`/`filial_id`; egress reverses to the configured tenant key.
- Pipeline core behavior (consumers, CB, RocksDB, Redis state, MANUAL ack, idempotency) is preserved — only field names + boundary mapping change.
- **Maven NOT on PATH.** Test (PowerShell): `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test` (add `-Dtest=ClassName` for focus). Full suite green after each task. Baseline before C-faza: 58 tests.
- Tenant settings live in B1's SettingsService (already migrated): `gateway.tenant.field-name` (default `company_id`), `gateway.tenant.extra-field-name` (default `filial_id`), `gateway.tenant.default-rest-tenant` (default `0`).
- Every task TDD: failing test → RED → implement → GREEN → commit.

## Fayl xaritasi

- **Rename (Task 1):** `model/kafka/RequestMessage`, `ResponseMessage`, `DlqMessage`; `model/Request`, `Response`, `ResponseSaveRequest`; `kafka/consumer/oracle/OracleRequestConsumer`, `OracleResponseConsumer`; `kafka/consumer/api/ApiRequestConsumer`; `http/HttpRequestService`; `api/ProxyController`; `validation/RequestValidator`; `oauth2/OAuth2ProviderService`, `OAuth2ProviderFetcher`; `notification/TelegramNotificationService`; `oracle/BiruniClient`; `controller/TestController`; + affected tests.
- **New (Task 2):** `tenant/TenantMappingService.java`, `tenant/TenantPair.java`.
- **Egress (Task 3):** `BiruniClient`, `OAuth2ProviderFetcher`, `ResponseSaveRequest`, `OracleResponseConsumer` touch-ups.
- **REST (Task 4):** `auth/AuthResult`, `api/ProxyController`, `auth/NoOpAuthorizationProvider`.
- **B2 (Task 5):** `admin/SettingsController` (validation), `admin/TenantMappingController.java`, `admin/dto/TenantMappingTestRequest.java`.
- **Docs (Task 6):** `ai-context/*`, `VERSION.md`.

---

### Task 1: Internal rename (atomic — all models + callers + tests, wire annotations KEPT)

**Files (Modify):** all model + caller + test files listed below. This is one atomic compile-passing rename. Wire `@JsonProperty("company_id")`/`("filial_id")` are KEPT on the renamed fields so behavior is byte-identical (dynamic mapping arrives in Task 2).

**Interfaces:**
- Produces: `RequestMessage.getTenantId()/setTenantId(Long)`, `getExtraTenantId()/setExtraTenantId(Long)`, builder `.tenantId(...)/.extraTenantId(...)`, `getCompositeId()` = `tenantId + ":" + requestId`. Same for `ResponseMessage` (tenantId), `DlqMessage` (tenantId + `from(...)` uses `getTenantId`), `Request`, `Response`, `ResponseSaveRequest` (tenantId). No more `getCompanyId`/`getFilialId` anywhere.

- [ ] **Step 1: Rename RequestMessage fields**

In `src/main/java/uz/greenwhite/gateway/model/kafka/RequestMessage.java`:
- `private Long companyId;` (with `@JsonProperty("company_id")`) → keep the annotation, rename field to `tenantId`:
```java
    @JsonProperty("company_id")
    private Long tenantId;
```
- `private Long filialId;` (with `@JsonProperty("filial_id")`) → keep annotation, rename to `extraTenantId`:
```java
    @JsonProperty("filial_id")
    private Long extraTenantId;
```
- `getCompositeId()`:
```java
    public String getCompositeId() {
        return tenantId + ":" + requestId;
    }
```

- [ ] **Step 2: Rename the other 5 models**

- `model/kafka/ResponseMessage.java`: `private Long companyId;` → `private Long tenantId;`; `getCompositeId()` → `return tenantId + ":" + requestId;`.
- `model/kafka/DlqMessage.java`: `private Long companyId;` → `private Long tenantId;`; `getCompositeId()` → `tenantId + ...`; in `from(...)` change `.companyId(request.getCompanyId())` → `.tenantId(request.getTenantId())`.
- `model/Request.java`: rename `companyId`→`tenantId` and (if present) `filialId`→`extraTenantId`. Read the file; rename every `companyId`/`filialId` field + any `@JsonProperty` stays as-is on the renamed field.
- `model/Response.java`: rename `companyId`→`tenantId` (3 occurrences — field + any usage).
- `model/ResponseSaveRequest.java`: `@JsonProperty("company_id") private Long companyId;` → keep annotation, rename field to `tenantId`.

- [ ] **Step 3: Update all callers (compiler-driven)**

Update every reference in these files (getters/setters/builders):
- `kafka/consumer/oracle/OracleRequestConsumer.java`: `.companyId(...)`/`getCompanyId()` in the DlqMessage build and error ResponseMessage build → `.tenantId(...)`/`getTenantId()` (2 occurrences).
- `kafka/consumer/oracle/OracleResponseConsumer.java`: `saveErrorResponse` + `buildSaveRequest` use `.companyId(message.getCompanyId())` → `.tenantId(message.getTenantId())` (2 occurrences).
- `kafka/consumer/api/ApiRequestConsumer.java`: same as Oracle consumer (2 occurrences).
- `http/HttpRequestService.java`: all `request.getCompanyId()` and `.companyId(request.getCompanyId())` in `buildOAuth2ErrorResponse`, `buildCircuitBreakerResponse`, `buildSuccessResponse`, `buildErrorResponse`, `buildSystemErrorResponse`, plus `oAuth2ProviderService.getToken(request.getOauth2Provider(), request.getCompanyId())` → `getTenantId()` (6 occurrences).
- `api/ProxyController.java`: `API_COMPANY_ID` constant + `.companyId(API_COMPANY_ID)` in the RequestMessage builder → rename constant to `API_DEFAULT_TENANT` and `.tenantId(API_DEFAULT_TENANT)` (3 occurrences). (Task 4 replaces this with the principal tenant.)
- `validation/RequestValidator.java`: `getCompanyId()` null/validity checks → `getTenantId()` (2 occurrences).
- `oauth2/OAuth2ProviderService.java`: `getToken(String providerName, Long companyId)` param + its use → rename param to `tenantId` (2 occurrences).
- `oauth2/OAuth2ProviderFetcher.java`: `fetch(String providerName, Long companyId)`, `fetchFromOracle(..., Long companyId)`, and the `Map.of("company_id", companyId, ...)` — rename the Java param `companyId`→`tenantId` but KEEP the map KEY string `"company_id"` (that's the wire contract, moved in Task 3). (4 occurrences: 2 param renames, the map key string stays.)
- `notification/TelegramNotificationService.java`: `msg.getCompanyId()` → `msg.getTenantId()` (1 occurrence). (The Russian label "Компания" text may stay or become "Тенант" — keep "Компания" for now, Task 6 can adjust.)
- `oracle/BiruniClient.java`: `request.getCompanyId()` in the saveResponse debug log (2 occurrences) → `getTenantId()`.
- `controller/TestController.java`: `.companyId(...)`/`getCompanyId()` in test payload builders → `.tenantId(...)` (6 occurrences).

- [ ] **Step 4: Update tests that reference the renamed builders**

Search and update: `grep -rl "companyId\|filialId\|getCompanyId" src/test`. Expected files: `HttpRequestServiceTest` (`.companyId(1L)` → `.tenantId(1L)`), `OracleRequestConsumerTest` (`RequestMessage.builder().companyId(1L).requestId(2L)` → `.tenantId(1L)`), `TelegramNotificationServiceTest` (`DlqMessage.builder().companyId(1L)` → `.tenantId(1L)`), and any others the grep surfaces. Update every `.companyId(`→`.tenantId(`, `.filialId(`→`.extraTenantId(`, `getCompanyId()`→`getTenantId()`.

- [ ] **Step 5: Verify build + full suite (this is the RED→GREEN for a rename)**

Run: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: BUILD SUCCESS, 58 tests, 0 failures. If compile fails, the error names the missed caller — fix and re-run. A rename is "green when it compiles and the unchanged tests pass."

- [ ] **Step 6: Ghost grep**

Run: `grep -rn "companyId\|getCompanyId\|filialId\|getFilialId" src/main` → expected: ZERO matches (all renamed). `grep -rn "company_id\|filial_id" src/main` → expected: only the `@JsonProperty("company_id")`/`("filial_id")` annotations (RequestMessage, ResponseSaveRequest) and the `OAuth2ProviderFetcher` map key `"company_id"` — these are the wire strings that move to TenantMappingService in Tasks 2-3.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(tenant): rename companyId/filialId to tenantId/extraTenantId (internal, wire unchanged)"
```

---

### Task 2: TenantMappingService + Biruni pull ingress

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/tenant/TenantPair.java`
- Create: `src/main/java/uz/greenwhite/gateway/tenant/TenantMappingService.java`
- Modify: `src/main/java/uz/greenwhite/gateway/model/kafka/RequestMessage.java` (remove wire annotations on tenant/extra)
- Modify: `src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java` (pull via JsonNode → toInternal)
- Test: `src/test/java/uz/greenwhite/gateway/tenant/TenantMappingServiceTest.java`

**Interfaces:**
- Consumes: `SettingsService.get(key, default)` (B1), `ObjectMapper` (Spring bean), `RequestMessage` setters (Task 1).
- Produces: `TenantMappingService.toInternal(JsonNode) → RequestMessage`, `toExternal(Long tenantId) → Map<String,Object>` (used in Task 3). `TenantPair(Long tenantId, Long extraTenantId)`.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantMappingServiceTest {

    @Mock SettingsService settings;
    private final ObjectMapper om = new ObjectMapper();

    private TenantMappingService service() {
        return new TenantMappingService(settings, om);
    }

    @Test
    void toInternalReadsDefaultCompanyIdFilialId() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("filial_id");
        var node = om.readTree("{\"company_id\":100,\"filial_id\":5,\"request_id\":7,\"base_url\":\"http://x\"}");

        RequestMessage msg = service().toInternal(node);

        assertEquals(100L, msg.getTenantId());
        assertEquals(5L, msg.getExtraTenantId());
        assertEquals(7L, msg.getRequestId());
        assertEquals("http://x", msg.getBaseUrl());
    }

    @Test
    void toInternalReadsConfiguredFieldNames() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("org_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("branch_id");
        var node = om.readTree("{\"org_id\":42,\"branch_id\":9,\"request_id\":1}");

        RequestMessage msg = service().toInternal(node);

        assertEquals(42L, msg.getTenantId());
        assertEquals(9L, msg.getExtraTenantId());
    }

    @Test
    void toInternalMissingTenantYieldsNull() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("filial_id");
        var node = om.readTree("{\"request_id\":1}"); // no tenant field

        RequestMessage msg = service().toInternal(node);

        assertNull(msg.getTenantId());
        assertNull(msg.getExtraTenantId());
    }

    @Test
    void toInternalBlankExtraFieldSkipsExtra() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("");
        var node = om.readTree("{\"company_id\":100,\"filial_id\":5,\"request_id\":1}");

        RequestMessage msg = service().toInternal(node);

        assertEquals(100L, msg.getTenantId());
        assertNull(msg.getExtraTenantId()); // extra field disabled
    }

    @Test
    void toExternalEmitsConfiguredTenantKey() {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        Map<String, Object> out = service().toExternal(100L);
        assertEquals(Map.of("company_id", 100L), out);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=TenantMappingServiceTest`
Expected: FAIL — `TenantMappingService`/`TenantPair` do not exist.

- [ ] **Step 3: Create TenantPair**

```java
package uz.greenwhite.gateway.tenant;

/** Extracted tenant identifiers from a boundary payload. */
public record TenantPair(Long tenantId, Long extraTenantId) {}
```

- [ ] **Step 4: Create TenantMappingService**

```java
package uz.greenwhite.gateway.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anti-corruption boundary between the main app's field names and the internal
 * tenant model. Ingress ({@link #toInternal}) reads the UI-configured field
 * names (default company_id/filial_id) from a Biruni pull JSON element into
 * tenantId/extraTenantId. Egress ({@link #toExternal}) reverses tenantId back to
 * the configured key so bmb receives the shape it expects. Field names are read
 * per call from SettingsService (dynamic — a UI change takes effect immediately).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMappingService {

    private static final String FIELD_KEY = "gateway.tenant.field-name";
    private static final String EXTRA_KEY = "gateway.tenant.extra-field-name";
    private static final String DEFAULT_FIELD = "company_id";
    private static final String DEFAULT_EXTRA = "filial_id";

    private final SettingsService settings;
    private final ObjectMapper objectMapper;

    /** INGRESS: build a RequestMessage from a raw Biruni pull element. */
    public RequestMessage toInternal(JsonNode element) {
        RequestMessage msg = objectMapper.convertValue(element, RequestMessage.class);
        TenantPair pair = extract(element);
        msg.setTenantId(pair.tenantId());
        msg.setExtraTenantId(pair.extraTenantId());
        return msg;
    }

    /** Extract tenant/extra from a payload using the configured field names. */
    public TenantPair extract(JsonNode element) {
        String field = settings.get(FIELD_KEY, DEFAULT_FIELD);
        String extraField = settings.get(EXTRA_KEY, DEFAULT_EXTRA);
        Long tenant = readLong(element, field);
        Long extra = (extraField != null && !extraField.isBlank()) ? readLong(element, extraField) : null;
        return new TenantPair(tenant, extra);
    }

    /** EGRESS: configured tenant key → value, for the Biruni save / OAuth fetch body. */
    public Map<String, Object> toExternal(Long tenantId) {
        String field = settings.get(FIELD_KEY, DEFAULT_FIELD);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(field, tenantId);
        return out;
    }

    /** The currently-configured tenant field name (for diagnostics / the test endpoint). */
    public String tenantField() {
        return settings.get(FIELD_KEY, DEFAULT_FIELD);
    }

    /** The currently-configured extra-tenant field name (may be blank). */
    public String extraField() {
        return settings.get(EXTRA_KEY, DEFAULT_EXTRA);
    }

    private static Long readLong(JsonNode element, String field) {
        if (field == null || field.isBlank()) return null;
        JsonNode n = element.get(field);
        return (n != null && !n.isNull()) ? n.asLong() : null;
    }
}
```

Note: `convertValue(element, RequestMessage.class)` still populates tenantId/extraTenantId from the KEPT `@JsonProperty("company_id")`/`("filial_id")` annotations — but we immediately override them from the configured field names, so the annotations are now redundant. Step 5 removes them.

- [ ] **Step 5: Remove wire annotations from RequestMessage tenant/extra**

In `RequestMessage.java`, drop the `@JsonProperty("company_id")` above `tenantId` and `@JsonProperty("filial_id")` above `extraTenantId` (leave the fields):
```java
    private Long tenantId;
    // ...
    private Long extraTenantId;
```
(All other `@JsonProperty` on RequestMessage stay — they map the remaining Biruni pull fields via `convertValue`.)

- [ ] **Step 6: Wire BiruniClient.pullRequests through toInternal**

In `oracle/BiruniClient.java`, inject `TenantMappingService` (add to constructor) and change `pullRequests()` to fetch `List<JsonNode>` then map:
```java
import com.fasterxml.jackson.databind.JsonNode;
// ...
List<JsonNode> raw = restClient.get()
        .uri(properties.getRequestPullUri())
        .header(HttpHeaders.AUTHORIZATION,
                AuthUtil.generateBasicAuth(properties.getUsername(), properties.getPassword()))
        .retrieve()
        .toEntity(new ParameterizedTypeReference<List<JsonNode>>() {})
        .getBody();

if (raw == null || raw.isEmpty()) {
    return List.of();
}
List<RequestMessage> requests = raw.stream()
        .map(tenantMappingService::toInternal)
        .toList();
log.info("Pulled {} requests from data source", requests.size());
return requests;
```
Add the `TenantMappingService` field + constructor param (BiruniClient currently has an explicit-ish constructor — add the param and assign it).

- [ ] **Step 7: Run tests + full suite**

Run: `... mvn.cmd test`
Expected: TenantMappingServiceTest 5/5 + full suite green (63 tests). If `RequestValidator` rejects null tenant, that's correct behavior (null tenant → validation fail → DLQ, per spec risk row) — existing tests shouldn't hit it.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(tenant): TenantMappingService + Biruni pull ingress via configurable field names"
```

---

### Task 3: Egress — Biruni save + OAuth fetch emit configured tenant key

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/model/ResponseSaveRequest.java` (drop static company_id annotation)
- Modify: `src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java` (saveResponse builds map via toExternal)
- Modify: `src/main/java/uz/greenwhite/gateway/oauth2/OAuth2ProviderFetcher.java` (fetch body via toExternal)
- Test: `src/test/java/uz/greenwhite/gateway/oracle/BiruniClientEgressTest.java`

**Interfaces:**
- Consumes: `TenantMappingService.toExternal(Long)` (Task 2).
- Produces: Biruni save JSON and OAuth fetch JSON both carry the CONFIGURED tenant key (default `company_id`), read from `ResponseSaveRequest.getTenantId()` / the fetch's `tenantId` param.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BiruniClientEgressTest {

    @Mock SettingsService settings;

    @Test
    void toExternalDefaultKeyIsCompanyId() {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        TenantMappingService mapper = new TenantMappingService(settings, new ObjectMapper());
        Map<String, Object> body = mapper.toExternal(100L);
        assertTrue(body.containsKey("company_id"));
        assertEquals(100L, body.get("company_id"));
        assertFalse(body.containsKey("tenant_id")); // never leaks the internal name
    }

    @Test
    void toExternalHonorsConfiguredKey() {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("org_id");
        TenantMappingService mapper = new TenantMappingService(settings, new ObjectMapper());
        assertEquals(Map.of("org_id", 7L), mapper.toExternal(7L));
    }
}
```

(This test pins the egress contract at the mapper level. The wiring below is verified by the existing consumer/fetcher tests staying green + the Task 6 smoke.)

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=BiruniClientEgressTest`
Expected: PASS actually (toExternal exists from Task 2) — so this test is a guard, not RED. If it compiles and passes, proceed; the RED for this task is the wiring change below breaking the old hardcoded path. Skip strict RED here (egress wiring is a refactor covered by existing tests).

- [ ] **Step 3: ResponseSaveRequest — drop the static company_id annotation**

In `model/ResponseSaveRequest.java`, remove `@JsonProperty("company_id")` above `tenantId` (the field was renamed in Task 1). The tenant key is now injected dynamically by `BiruniClient.saveResponse`, not by Jackson:
```java
    private Long tenantId;
```
(Keep `@JsonProperty("request_id")`, `("response")`, `("error_message")`.)

- [ ] **Step 4: BiruniClient.saveResponse builds the body via toExternal**

In `oracle/BiruniClient.java`, change `saveResponse` to build a `Map<String,Object>` merging the configured tenant key with the rest, instead of posting the typed `ResponseSaveRequest` directly:
```java
@Override
public boolean saveResponse(ResponseSaveRequest request) {
    try {
        Map<String, Object> body = new java.util.LinkedHashMap<>(
                tenantMappingService.toExternal(request.getTenantId()));
        body.put("request_id", request.getRequestId());
        body.put("response", request.getResponse());
        body.put("error_message", request.getErrorMessage());

        var response = restClient.post()
                .uri(properties.getResponseSaveUri())
                .header(HttpHeaders.AUTHORIZATION,
                        AuthUtil.generateBasicAuth(properties.getUsername(), properties.getPassword()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(body))
                .exchange((req, resp) -> {
                    int status = resp.getStatusCode().value();
                    log.debug("Save response status: {}", status);
                    return status >= 200 && status < 300;
                });
        if (response) {
            log.info("Response saved successfully: {}:{}", request.getTenantId(), request.getRequestId());
        }
        return response;
    } catch (Exception e) {
        log.error("Error saving response to data source: {}", e.getMessage(), e);
        return false;
    }
}
```
(`ResponseSaveRequest.ResponseData` still serializes with its own `@JsonProperty` sub-fields inside the `"response"` value.)

- [ ] **Step 5: OAuth2ProviderFetcher — fetch body via toExternal**

In `oauth2/OAuth2ProviderFetcher.java`, inject `TenantMappingService` and change `fetchFromOracle`'s body build:
```java
Map<String, Object> body = new java.util.LinkedHashMap<>(tenantMappingService.toExternal(tenantId));
body.put("oauth2_provider", providerName);
```
(Replaces the hardcoded `Map.of("company_id", tenantId, "oauth2_provider", providerName)`. The `tenantId` param was renamed in Task 1.)

- [ ] **Step 6: Run tests + full suite**

Run: `... mvn.cmd test`
Expected: 65 tests, 0 failures. Existing consumer/fetcher tests still green; new egress guard test green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(tenant): Biruni save + OAuth fetch egress emit configured tenant key via toExternal"
```

---

### Task 4: REST /queue tenant from auth principal

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/auth/AuthResult.java` (add optional tenantId/extraTenantId)
- Modify: `src/main/java/uz/greenwhite/gateway/api/ProxyController.java` (read principal tenant + default fallback + X-Extra-Tenant-Id)
- Test: `src/test/java/uz/greenwhite/gateway/auth/AuthResultTest.java`

**Interfaces:**
- Produces: `AuthResult.getTenantId():Long` (nullable), `getExtraTenantId():String` (nullable); `AuthResult.allowed(Long tenantId, String extraTenantId)` factory; existing `allowed()` returns null tenant. `ProxyController` sets `RequestMessage.tenantId` from `authResult.getTenantId()`, else the `gateway.tenant.default-rest-tenant` setting (parsed to Long).

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthResultTest {

    @Test
    void allowedNoTenantHasNullTenant() {
        AuthResult r = AuthResult.allowed();
        assertTrue(r.isAllowed());
        assertNull(r.getTenantId());
        assertNull(r.getExtraTenantId());
    }

    @Test
    void allowedWithTenantCarriesIt() {
        AuthResult r = AuthResult.allowed(42L, "branch-9");
        assertTrue(r.isAllowed());
        assertEquals(42L, r.getTenantId());
        assertEquals("branch-9", r.getExtraTenantId());
    }

    @Test
    void deniedHasNullTenant() {
        AuthResult r = AuthResult.denied("nope");
        assertFalse(r.isAllowed());
        assertNull(r.getTenantId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=AuthResultTest`
Expected: FAIL — `getTenantId()`/`allowed(Long,String)` don't exist.

- [ ] **Step 3: Extend AuthResult**

Rewrite `auth/AuthResult.java` keeping the immutable style:
```java
package uz.greenwhite.gateway.auth;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Outcome of an authorize() call. Carries the resolved tenant (nullable) so the
 * proxy can tag the request without trusting a client-supplied value. Construct
 * via the static factories.
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthResult {

    private boolean allowed;
    private String reason;
    private int httpStatus;
    /** Resolved tenant id; null when the provider does not supply one (e.g. NoOp). */
    private Long tenantId;
    /** Resolved extra-tenant; null when not supplied. */
    private String extraTenantId;

    public static AuthResult allowed() {
        return new AuthResult(true, null, 200, null, null);
    }

    public static AuthResult allowed(Long tenantId, String extraTenantId) {
        return new AuthResult(true, null, 200, tenantId, extraTenantId);
    }

    public static AuthResult denied(String reason) {
        return new AuthResult(false, reason, 403, null, null);
    }

    public static AuthResult denied(String reason, int httpStatus) {
        return new AuthResult(false, reason, httpStatus, null, null);
    }
}
```
(No `NoOpAuthorizationProvider` change needed — it calls `allowed()`, which now yields null tenant → ProxyController falls back to the default.)

- [ ] **Step 4: ProxyController uses the principal tenant + default fallback**

In `api/ProxyController.java`: inject `SettingsService` (add to `@RequiredArgsConstructor` field list, LAST). Replace the `API_DEFAULT_TENANT` constant usage. After the auth block (where `authResult` is available), resolve the tenant:
```java
Long tenantId = authResult.getTenantId();
if (tenantId == null) {
    tenantId = Long.parseLong(settingsService.get("gateway.tenant.default-rest-tenant", "0"));
}
String extraTenantHeader = request.getHeader("X-Extra-Tenant-Id");
Long extraTenantId = null;
if (authResult.getExtraTenantId() != null) {
    // provider-supplied extra tenant wins (string → keep as-is only if numeric; else null)
    extraTenantId = parseLongOrNull(authResult.getExtraTenantId());
} else if (extraTenantHeader != null && !extraTenantHeader.isBlank()) {
    extraTenantId = parseLongOrNull(extraTenantHeader);
}
```
Add a small private helper:
```java
private static Long parseLongOrNull(String s) {
    try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
}
```
Change the `RequestMessage.builder()` to `.tenantId(tenantId).extraTenantId(extraTenantId)` (replacing `.tenantId(API_DEFAULT_TENANT)` from Task 1). Remove the now-unused `API_DEFAULT_TENANT` constant.

- [ ] **Step 5: Run tests + full suite**

Run: `... mvn.cmd test`
Expected: 68 tests, 0 failures. `ProxyControllerIpTest` constructs `ProxyController` — its constructor now has one more arg (`SettingsService`); update that test's constructor call to pass a mock/null `SettingsService` (it only exercises `getClientIp`, so `null` is fine for the added trailing param). Confirm the arg order in the file and update the test.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(tenant): REST /queue tenant from auth principal with default-rest-tenant fallback"
```

---

### Task 5: B2 — typed validation + mapping-test endpoint

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/admin/SettingsController.java` (validate tenant keys on PUT)
- Create: `src/main/java/uz/greenwhite/gateway/admin/TenantMappingController.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/dto/TenantMappingTestRequest.java`
- Test: `src/test/java/uz/greenwhite/gateway/admin/TenantMappingApiTest.java`

**Interfaces:**
- Consumes: `SettingsService.set/get` (B1), `TenantMappingService.extract(JsonNode)`, `tenantField()`, `extraField()` (Task 2).
- Produces: `PUT /qosid/admin/api/settings/gateway.tenant.field-name` with blank value → 400; `POST /qosid/admin/api/tenant-mapping/test` (admin session) → `{tenantId, extraTenantId, tenantField, extraField}`.

- [ ] **Step 1: Write the failing test**

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
        "gateway.settings.db-path=mem:tenant_api_test;DB_CLOSE_DELAY=-1",
        "gateway.rocksdb.path=./target/test-data/rocksdb-tenant-api",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class TenantMappingApiTest {

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
    void blankTenantFieldNameRejected() throws Exception {
        MockHttpSession s = login();
        mvc.perform(put("/qosid/admin/api/settings/gateway.tenant.field-name")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "", "secret", false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mappingTestExtractsTenant() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/qosid/admin/api/tenant-mapping/test")
                        .session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company_id\":100,\"filial_id\":5,\"request_id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(100))
                .andExpect(jsonPath("$.extraTenantId").value(5))
                .andExpect(jsonPath("$.tenantField").value("company_id"));
    }

    @Test
    void mappingTestRequiresAuth() throws Exception {
        mvc.perform(post("/qosid/admin/api/tenant-mapping/test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=TenantMappingApiTest`
Expected: FAIL — no tenant validation, no `/tenant-mapping/test` endpoint.

- [ ] **Step 3: Add tenant-key validation to SettingsController.update**

In `admin/SettingsController.java`, at the start of the `update(...)` method (before `settingsService.set`):
```java
if ("gateway.tenant.field-name".equals(key)
        && (req.getValue() == null || req.getValue().isBlank())) {
    throw new IllegalArgumentException("gateway.tenant.field-name must not be blank");
}
if ("gateway.tenant.default-rest-tenant".equals(key)) {
    try { Long.parseLong(req.getValue().trim()); }
    catch (Exception e) { throw new IllegalArgumentException("default-rest-tenant must be numeric"); }
}
```
(`IllegalArgumentException` → 400 via the handler added in B1 Task 8's security fix.)

- [ ] **Step 4: Create the test-request DTO**

`admin/dto/TenantMappingTestRequest.java` — actually the endpoint takes a raw JSON body, so bind it as `JsonNode`. No DTO needed; skip this file. (If your controller signature needs a type, use `com.fasterxml.jackson.databind.JsonNode`.)

- [ ] **Step 5: Create TenantMappingController**

```java
package uz.greenwhite.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.tenant.TenantMappingService;
import uz.greenwhite.gateway.tenant.TenantPair;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin diagnostics for the tenant mapping. Lets an operator paste a sample
 * Biruni pull element and see which tenant/extra the current configuration
 * would extract — without needing a live pull or the D-faza UI.
 */
@RestController
@RequestMapping("/qosid/admin/api/tenant-mapping")
@RequiredArgsConstructor
public class TenantMappingController {

    private final TenantMappingService tenantMappingService;

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody JsonNode sample) {
        TenantPair pair = tenantMappingService.extract(sample);
        Map<String, Object> out = new HashMap<>();
        out.put("tenantId", pair.tenantId());
        out.put("extraTenantId", pair.extraTenantId());
        out.put("tenantField", tenantMappingService.tenantField());
        out.put("extraField", tenantMappingService.extraField());
        return ResponseEntity.ok(out);
    }
}
```
(It lives under `/qosid/admin/api/**`, so the B1 admin `SecurityFilterChain` already requires a session — the unauthenticated test asserts 401 automatically.)

- [ ] **Step 6: Run tests + full suite**

Run: `... mvn.cmd test`
Expected: 71 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(tenant): typed field-name validation + mapping-test admin endpoint (B2 folded in)"
```

---

### Task 6: Docs sync + version 1.5.0 + smoke

**Files:**
- Modify: `ai-context/ARCHITECTURE.md`, `ai-context/CONFIGURATION.md`, `ai-context/DOMAIN-GLOSSARY.md`, `ai-context/SAFETY-RULES.md`, `CLAUDE.md`
- Modify: `VERSION.md`

- [ ] **Step 1: DOMAIN-GLOSSARY.md**

Replace the `CompanyId` glossary entry with `TenantId` (internal id, was company_id; boundary-mapped from the configured field). Add `ExtraTenantId` (was filial_id; optional; not in keys). Note the wire field names are configurable via `gateway.tenant.*`.

- [ ] **Step 2: ARCHITECTURE.md**

Add a "Tenant boundary translation (C-faza)" subsection: internal core is tenantId/extraTenantId; `TenantMappingService.toInternal` at Biruni pull ingress, `toExternal` at Biruni save + OAuth fetch egress; REST tenant from auth principal + `default-rest-tenant` fallback; `compositeId=tenantId:requestId` (unchanged format, no migration). Update any `companyId` mentions in the data-flow section to `tenantId`.

- [ ] **Step 3: CONFIGURATION.md**

Confirm the `gateway.tenant.*` section (added in B1) documents field-name/extra-field-name/default-rest-tenant. Add: field-name must be non-blank (validated, 400); changing it takes effect on the NEXT pull (dynamic). Document the `POST /qosid/admin/api/tenant-mapping/test` endpoint and the `X-Extra-Tenant-Id` request header on `/queue`.

- [ ] **Step 4: SAFETY-RULES.md rule 30**

```markdown
### 30. Never bypass TenantMappingService at the Biruni boundary

Biruni pull ingress (`BiruniClient.pullRequests`) MUST route each element through
`TenantMappingService.toInternal`, and Biruni save + OAuth fetch egress MUST build
their tenant key via `toExternal`. The internal model uses `tenantId`/`extraTenantId`
with NO `@JsonProperty("company_id")` binding — deserializing a Biruni pull directly
into `RequestMessage` again would leave `tenantId` null (all requests → DLQ). The
default field names are `company_id`/`filial_id` so bmb is unaffected; hardcoding the
key instead of reading the setting breaks any deployment that reconfigures it.
```

- [ ] **Step 5: CLAUDE.md**

Update the intro/glossary mention of CompanyId → TenantId. Add the `/qosid/admin/api/tenant-mapping/test` row to the admin API description. Update the DOMAIN-GLOSSARY bullet (`CompanyId` → `TenantId`).

- [ ] **Step 6: Ghost grep + version**

Run: `grep -rn "companyId\|filialId" src/ ai-context/ CLAUDE.md` → expected: no stale internal references (only historical notes if any). `VERSION.md` → `1.5.0`.

- [ ] **Step 7: Full build + test + smoke**

Run:
```
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests -q
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test
```
Expected: BUILD SUCCESS, 71 tests, 0 failures.

Smoke (Docker RedPanda+Redis up): run the jar with `SETTINGS_ENCRYPTION_KEY=smoke-key-0123456789 POLLING_ENABLED=false SETTINGS_DB_PATH=./target/smoke-c ROCKSDB_PATH=./target/smoke-c-rocks`, wait for "Started", then:
- Login (CSRF cookie-echo flow as in B1 smoke) → 200.
- `POST /qosid/admin/api/tenant-mapping/test` (with session) body `{"company_id":100,"filial_id":5}` → 200 `{"tenantId":100,"extraTenantId":5,"tenantField":"company_id",...}`.
- `PUT /qosid/admin/api/settings/gateway.tenant.field-name` value "" → 400.
- `GET /qosid/api/v1/requests/0:1/status` (no auth) → 404 (pipeline still open).
Capture outputs. Stop the app.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "docs: tenant rename + boundary translation (SAFETY rule 30, glossary); bump version to 1.5.0"
```

---

## Yakuniy eslatmalar (executor uchun)

- Task 1 (rename) atomik — kompilyator to'liqlikni ta'minlaydi; 58 test yashil = muvaffaqiyat.
- `ApiRequestConsumer`/`OracleRequestConsumer` mirror-twin (SAFETY §10) — har ikkisiga alohida tegiladi.
- Default mapping `company_id`/`filial_id` — bmb tegilmaydi; smoke buni tasdiqlaydi.
- REST tenant B4'gacha `default-rest-tenant` fallback bilan ishlaydi (kutilgan).
- Release (staging/master) — plan tugagach ALOHIDA manual qadam.
