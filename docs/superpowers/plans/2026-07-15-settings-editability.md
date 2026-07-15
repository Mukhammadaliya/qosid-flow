# Settings tahrirlanuvchanligi + color-coding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `gateway.*` sozlamalarini admin UI'dan haqiqatan tahrirlanadigan va ta'sir qiladigan qilish; har maydonni effect-class bo'yicha rang bilan ajratish (🟢 live / 🟡 requires-restart / ⚪ read-only).

**Architecture:** Uch qatlam — (1) `SettingsEnvironmentPostProcessor` boot'da settings-DB'ni Spring `Environment`'ga qatlam qilib qo'shadi (barcha `@ConfigurationProperties` restart'da DB override oladi); (2) endpoint iste'mol nuqtalari `SettingsService.get()` orqali jonli o'qiydi; (3) `SettingsRegistry` har kalitning guruh/label/tur/effect-class metama'lumotini beradi (UI + validatsiya).

**Tech Stack:** Java 21, Spring Boot 3.3, H2 (settings DB), JdbcTemplate, JUnit 5 + Mockito; React 18 + TypeScript + Ant Design + Vitest.

## Global Constraints

- Ish `debug` branch'da (CLAUDE.md branching policy) — `master`'ga tegilmaydi.
- Paket: `uz.greenwhite.gateway`.
- **Pipeline (E1-E5) core'ga tegilmaydi** (SAFETY 1-5): faqat `ProxyController.java:198` va `BiruniClient` URL o'qishlari o'zgaradi — CB/retry/idempotency/ack/Kafka-ack mantig'iga TEGILMAYDI.
- Dinamik o'qish API'si: `SettingsService.get(String key, String defaultValue)` (mavjud).
- PostProcessor PropertySource tartibi: `systemEnvironment`'dan **past**, `application.yml`'dan **yuqori** (infra env-var'lar g'olib qoladi).
- PostProcessor faqat **non-secret** qatorlarni yuklaydi; DB o'qib bo'lmasa **never-fail** (yaml default ishlaydi).
- Effect-class qiymatlari: `LIVE`, `REQUIRES_RESTART`, `READ_ONLY`.
- Auto-restart YO'Q: 🟡 saqlangach faqat restart-pending bayrog'i.
- Sirlar AES-shifrlangan qoladi, GET'da `***` bilan masklanadi.
- Admin-auth chain (`/qosid/admin/api/**`, B1) o'zgarmaydi.
- i18n: `frontend/src/locales/uz.json`.
- Reyestrda bo'lmagan `gateway.*` kalit → default `REQUIRES_RESTART`.

---

### Task 1: SettingsRegistry — kalit metama'lumoti + tasnif

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/settings/SettingsRegistry.java`
- Test: `src/test/java/uz/greenwhite/gateway/settings/SettingsRegistryTest.java`

**Interfaces:**
- Produces:
  - `enum SettingsRegistry.EffectClass { LIVE, REQUIRES_RESTART, READ_ONLY }`
  - `record SettingsRegistry.SettingMeta(String key, String group, String label, String type, EffectClass effectClass, boolean secret)`
  - `List<SettingMeta> SettingsRegistry.all()` — ordered known settings
  - `EffectClass SettingsRegistry.effectClassOf(String key)` — reyestrda yo'q bo'lsa `REQUIRES_RESTART`
  - `boolean SettingsRegistry.isReadOnly(String key)`

- [ ] **Step 1: Write the failing test**

`src/test/java/uz/greenwhite/gateway/settings/SettingsRegistryTest.java`:
```java
package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.settings.SettingsRegistry.EffectClass;

import static org.junit.jupiter.api.Assertions.*;

class SettingsRegistryTest {

    @Test
    void classifiesKnownKeys() {
        assertEquals(EffectClass.LIVE, SettingsRegistry.effectClassOf("gateway.api.base-url"));
        assertEquals(EffectClass.LIVE, SettingsRegistry.effectClassOf("gateway.source.base-url"));
        assertEquals(EffectClass.REQUIRES_RESTART, SettingsRegistry.effectClassOf("gateway.retry.max-attempts"));
        assertEquals(EffectClass.REQUIRES_RESTART, SettingsRegistry.effectClassOf("gateway.polling.enabled"));
        assertEquals(EffectClass.READ_ONLY, SettingsRegistry.effectClassOf("server.port"));
    }

    @Test
    void unknownGatewayKeyDefaultsToRequiresRestart() {
        assertEquals(EffectClass.REQUIRES_RESTART, SettingsRegistry.effectClassOf("gateway.some.future.key"));
    }

    @Test
    void isReadOnlyMatchesRegistry() {
        assertTrue(SettingsRegistry.isReadOnly("server.port"));
        assertFalse(SettingsRegistry.isReadOnly("gateway.api.base-url"));
    }

    @Test
    void allEntriesHaveGroupAndLabel() {
        assertFalse(SettingsRegistry.all().isEmpty());
        SettingsRegistry.all().forEach(m -> {
            assertNotNull(m.group());
            assertFalse(m.group().isBlank());
            assertNotNull(m.label());
            assertFalse(m.label().isBlank());
            assertNotNull(m.effectClass());
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SettingsRegistryTest`
Expected: FAIL — `SettingsRegistry` klassi yo'q (compile error).

- [ ] **Step 3: Write minimal implementation**

`src/main/java/uz/greenwhite/gateway/settings/SettingsRegistry.java`:
```java
package uz.greenwhite.gateway.settings;

import java.util.List;

/**
 * Static metadata for known gateway settings: group, human label, input type,
 * and effect-class (how a change takes effect). Drives the settings UI color-coding
 * and backend validation. Keys not listed default to REQUIRES_RESTART (safe assumption).
 * Secrets and source-auth/AI keys are managed by their dedicated pages, not here.
 */
public final class SettingsRegistry {

    private SettingsRegistry() {}

    public enum EffectClass { LIVE, REQUIRES_RESTART, READ_ONLY }

    public record SettingMeta(String key, String group, String label, String type,
                              EffectClass effectClass, boolean secret) {}

    private static final List<SettingMeta> SETTINGS = List.of(
        // 🟢 LIVE — endpoints & simple runtime values (read per-use via SettingsService)
        new SettingMeta("gateway.api.base-url", "Endpointlar", "API proxy manzili", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.source.base-url", "Endpointlar", "Source base-url", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.source.request-pull-uri", "Endpointlar", "Source pull URI", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.source.response-save-uri", "Endpointlar", "Source save URI", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.response.threshold-size", "Umumiy", "Inline javob chegarasi (bayt)", "number", EffectClass.LIVE, false),
        new SettingMeta("gateway.loki.url", "Observability", "Loki URL", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.prometheus.url", "Observability", "Prometheus URL", "url", EffectClass.LIVE, false),
        new SettingMeta("gateway.tenant.field-name", "Tenant", "Tenant field name", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.tenant.extra-field-name", "Tenant", "Extra tenant field name", "text", EffectClass.LIVE, false),
        new SettingMeta("gateway.tenant.default-rest-tenant", "Tenant", "Default REST tenant", "number", EffectClass.LIVE, false),
        // 🟡 REQUIRES_RESTART — bound into beans at startup; persisted, applied on next boot
        new SettingMeta("gateway.retry.max-attempts", "Retry/HTTP", "Max attempts", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.retry.interval-ms", "Retry/HTTP", "Retry interval (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.retry.retryable-statuses", "Retry/HTTP", "Retryable statuses", "text", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.retry.max-message-age-ms", "Retry/HTTP", "Max message age (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.http.connect-timeout-ms", "Retry/HTTP", "Connect timeout (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.http.read-timeout-ms", "Retry/HTTP", "Read timeout (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.polling.enabled", "Polling", "Polling yoqilgan", "boolean", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.polling.interval-ms", "Polling", "Polling interval (ms)", "number", EffectClass.REQUIRES_RESTART, false),
        new SettingMeta("gateway.polling.batch-size", "Polling", "Polling batch size", "number", EffectClass.REQUIRES_RESTART, false),
        // ⚪ READ_ONLY — bootstrap/infra, shown greyed, never editable (encryption key intentionally excluded)
        new SettingMeta("gateway.settings.db-path", "Infratuzilma", "Settings DB yo'li", "text", EffectClass.READ_ONLY, false),
        new SettingMeta("server.port", "Infratuzilma", "App port", "number", EffectClass.READ_ONLY, false),
        new SettingMeta("management.server.port", "Infratuzilma", "Management port", "number", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.kafka.bootstrap-servers", "Infratuzilma", "Kafka bootstrap", "text", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.data.redis.host", "Infratuzilma", "Redis host", "text", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.data.redis.port", "Infratuzilma", "Redis port", "number", EffectClass.READ_ONLY, false),
        new SettingMeta("spring.profiles.active", "Infratuzilma", "Spring profil", "text", EffectClass.READ_ONLY, false)
    );

    public static List<SettingMeta> all() {
        return SETTINGS;
    }

    public static EffectClass effectClassOf(String key) {
        return SETTINGS.stream()
                .filter(m -> m.key().equals(key))
                .map(SettingMeta::effectClass)
                .findFirst()
                .orElse(EffectClass.REQUIRES_RESTART);
    }

    public static boolean isReadOnly(String key) {
        return effectClassOf(key) == EffectClass.READ_ONLY;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=SettingsRegistryTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/settings/SettingsRegistry.java src/test/java/uz/greenwhite/gateway/settings/SettingsRegistryTest.java
git commit -m "feat: SettingsRegistry — settings metadata + effect-class classification"
```

---

### Task 2: SettingsEnvironmentPostProcessor — boot-vaqt DB→Environment qatlami

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/settings/SettingsEnvironmentPostProcessor.java`
- Create: `src/main/resources/META-INF/spring.factories`
- Test: `src/test/java/uz/greenwhite/gateway/settings/SettingsEnvironmentPostProcessorTest.java`

**Interfaces:**
- Consumes: hech narsa (boot-vaqt, context'dan oldin — bean inject qilib bo'lmaydi).
- Produces: `Environment`'ga `MapPropertySource` nomi `"settings-db"`, `application.yml`'dan yuqori.

**Muhim texnik faktlar (kod bazasidan):**
- H2 URL: `jdbc:h2:<db-path>;MODE=LEGACY` (SettingsDbConfig.java:28).
- Settings jadval: `settings(skey VARCHAR PK, svalue CLOB, secret BOOLEAN, ...)` (settings-schema.sql).
- db-path property nomi: `gateway.settings.db-path`, ENV `SETTINGS_DB_PATH`, default `./data/settings` (application.yml:248).
- Faqat non-secret qatorlar yuklanadi (secret'lar runtime'da `SettingsService.getSecret` orqali o'qiladi — Environment'ga kerak emas).

- [ ] **Step 1: Write the failing test**

`src/test/java/uz/greenwhite/gateway/settings/SettingsEnvironmentPostProcessorTest.java`:
```java
package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SettingsEnvironmentPostProcessorTest {

    private final SettingsEnvironmentPostProcessor processor = new SettingsEnvironmentPostProcessor();

    @Test
    void loadsNonSecretRowsAsProperties(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("settings").toString();
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath + ";MODE=LEGACY", "sa", "");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE settings (skey VARCHAR(200) PRIMARY KEY, svalue CLOB, secret BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMP, updated_by VARCHAR(100))");
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('gateway.api.base-url', 'https://db.example.com', FALSE)");
            s.execute("INSERT INTO settings (skey, svalue, secret) VALUES ('gateway.source.auth.basic.password', 'ENCRYPTED', TRUE)");
        }
        MockEnvironment env = new MockEnvironment();
        env.setProperty("gateway.settings.db-path", dbPath);

        processor.postProcessEnvironment(env, new SpringApplication());

        assertEquals("https://db.example.com", env.getProperty("gateway.api.base-url"));
        // secret row is NOT injected into the environment
        assertNull(env.getProperty("gateway.source.auth.basic.password"));
    }

    @Test
    void missingDatabaseIsSilentlyIgnored(@TempDir Path dir) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("gateway.settings.db-path", dir.resolve("does-not-exist-yet").toString());
        // No table exists → must not throw, environment unchanged for our key
        assertDoesNotThrow(() -> processor.postProcessEnvironment(env, new SpringApplication()));
        assertNull(env.getProperty("gateway.api.base-url"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SettingsEnvironmentPostProcessorTest`
Expected: FAIL — klass yo'q (compile error).

- [ ] **Step 3: Write minimal implementation**

`src/main/java/uz/greenwhite/gateway/settings/SettingsEnvironmentPostProcessor.java`:
```java
package uz.greenwhite.gateway.settings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Boot-time layer: reads the H2 settings DB and adds its non-secret rows to the
 * Environment as a property source ranked below systemEnvironment but above
 * application.yml. Every @ConfigurationProperties consumer (ApiProperties,
 * GatewayProperties, retry/http/polling) thus picks up DB overrides on boot,
 * WITHOUT per-consumer changes — this is what makes "requires-restart" settings apply.
 *
 * Secrets are NOT loaded here (they are read at runtime via SettingsService.getSecret).
 * Any failure (first boot, no table, no file) is swallowed: yaml defaults then apply.
 * Runs after ConfigData so gateway.settings.db-path is already resolved.
 */
public class SettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "settings-db";

    @Override
    public int getOrder() {
        // After config-data (yaml loaded) so db-path resolves; still early enough to bind properties.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dbPath = environment.getProperty("gateway.settings.db-path", "./data/settings");
        String url = "jdbc:h2:" + dbPath + ";MODE=LEGACY;IFEXISTS=TRUE";
        Map<String, Object> props = new HashMap<>();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT skey, svalue, secret FROM settings")) {
            while (rs.next()) {
                if (rs.getBoolean("secret")) continue;            // secrets stay out of the environment
                String key = rs.getString("skey");
                if (key.startsWith("gateway.settings.")) continue; // never re-inject bootstrap keys
                if (key.startsWith("gateway._meta.")) continue;    // internal flags are not properties
                String value = rs.getString("svalue");
                if (value != null) props.put(key, value);
            }
        } catch (Exception e) {
            // First boot / no DB file (IFEXISTS=TRUE) / no table → never-fail; yaml defaults apply.
            return;
        }
        if (props.isEmpty()) return;
        // addLast: ranks below systemEnvironment/systemProperties (added earlier by Spring),
        // above application.yml → env-vars win (read-only infra protected), DB overrides yaml.
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, props));
    }
}
```

**Eslatma (ordering):** `addLast` bilan qo'shilgan manba `systemEnvironment`/`systemProperties`'dan past (ular Spring tomonidan oldin qo'shilgan) va `application.yml`'dan yuqori (yaml odatda oxirida) bo'ladi — bu spec talabiga mos (env-var g'olib). Agar test aniqroq tartib talab qilsa, `addBefore("applicationConfig: [classpath:/application.yml]", ...)` ishlatiladi; `addLast` yetarli va soddaroq.

- [ ] **Step 4: Register the post-processor**

`src/main/resources/META-INF/spring.factories`:
```properties
org.springframework.boot.env.EnvironmentPostProcessor=\
uz.greenwhite.gateway.settings.SettingsEnvironmentPostProcessor
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=SettingsEnvironmentPostProcessorTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/settings/SettingsEnvironmentPostProcessor.java src/main/resources/META-INF/spring.factories src/test/java/uz/greenwhite/gateway/settings/SettingsEnvironmentPostProcessorTest.java
git commit -m "feat: SettingsEnvironmentPostProcessor — boot-time settings-DB overrides for @ConfigurationProperties"
```

---

### Task 3: Restart-pending bayrog'i (SettingsService + boot-clear runner)

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/settings/SettingsService.java`
- Create: `src/main/java/uz/greenwhite/gateway/settings/RestartPendingResetRunner.java`
- Test: `src/test/java/uz/greenwhite/gateway/settings/SettingsServiceRestartPendingTest.java`

**Interfaces:**
- Consumes: `SettingsService`, `SettingsRepository` (Task pre-existing).
- Produces:
  - `void SettingsService.markRestartPending()` — meta kalitni `true` qiladi (audit'siz).
  - `boolean SettingsService.isRestartPending()`.
  - `void SettingsService.clearRestartPending()`.
  - `RestartPendingResetRunner implements ApplicationRunner` — boot'da `clearRestartPending()`.

**Meta kalit:** `gateway._meta.restart-pending` (settings jadvalida, secret=false).

- [ ] **Step 1: Write the failing test**

`src/test/java/uz/greenwhite/gateway/settings/SettingsServiceRestartPendingTest.java`:
```java
package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceRestartPendingTest {

    @Mock SettingsRepository repo;
    @Mock SettingsCipher cipher;

    private SettingsService service() {
        SettingsService s = new SettingsService(repo, cipher);
        s.reload();
        return s;
    }

    @Test
    void markAndReadRestartPending() {
        when(repo.findAll()).thenReturn(List.of());
        SettingsService s = service();
        assertFalse(s.isRestartPending());

        s.markRestartPending();
        verify(repo).upsert("gateway._meta.restart-pending", "true", false, "system");
        assertTrue(s.isRestartPending());

        s.clearRestartPending();
        verify(repo).upsert("gateway._meta.restart-pending", "false", false, "system");
        assertFalse(s.isRestartPending());
    }

    @Test
    void markRestartPendingWritesNoAudit() {
        when(repo.findAll()).thenReturn(List.of());
        SettingsService s = service();
        s.markRestartPending();
        verify(repo, never()).insertAudit(any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SettingsServiceRestartPendingTest`
Expected: FAIL — `markRestartPending`/`isRestartPending`/`clearRestartPending` yo'q.

- [ ] **Step 3: Add methods to SettingsService**

`src/main/java/uz/greenwhite/gateway/settings/SettingsService.java` — klass ichiga (masalan `getAllMasked()` dan keyin) qo'shing:
```java
    /** Internal flag key: some requires-restart setting changed since the last boot. */
    public static final String RESTART_PENDING_KEY = "gateway._meta.restart-pending";

    /** Set the restart-pending flag (no audit row — it is an internal flag, not a user edit). */
    public void markRestartPending() {
        setFlag(RESTART_PENDING_KEY, "true");
    }

    /** Clear the flag — called on boot once DB values have already been applied. */
    public void clearRestartPending() {
        setFlag(RESTART_PENDING_KEY, "false");
    }

    public boolean isRestartPending() {
        return "true".equals(get(RESTART_PENDING_KEY, "false"));
    }

    private void setFlag(String key, String value) {
        repository.upsert(key, value, false, "system");
        cache.put(key, SettingsRecord.builder().key(key).value(value).secret(false).updatedBy("system").build());
    }
```

- [ ] **Step 4: Create the boot-clear runner**

`src/main/java/uz/greenwhite/gateway/settings/RestartPendingResetRunner.java`:
```java
package uz.greenwhite.gateway.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On every successful boot the DB settings have already been applied
 * (SettingsEnvironmentPostProcessor + fresh @ConfigurationProperties binding),
 * so any pending "restart required" flag is now satisfied. Clear it.
 * Ordered after SettingsMigrationRunner so first-boot migration finishes first.
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class RestartPendingResetRunner implements ApplicationRunner {

    private final SettingsService settingsService;

    @Override
    public void run(ApplicationArguments args) {
        settingsService.clearRestartPending();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=SettingsServiceRestartPendingTest,SettingsServiceTest`
Expected: PASS (mavjud `SettingsServiceTest` ham yashil qoladi — regressiya yo'q).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/settings/SettingsService.java src/main/java/uz/greenwhite/gateway/settings/RestartPendingResetRunner.java src/test/java/uz/greenwhite/gateway/settings/SettingsServiceRestartPendingTest.java
git commit -m "feat: restart-pending flag (mark on requires-restart edit, clear on boot)"
```

---

### Task 4: SettingsController — registry endpoint + PUT validatsiya

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/admin/dto/SettingsRegistryResponse.java`
- Modify: `src/main/java/uz/greenwhite/gateway/admin/SettingsController.java`
- Test: `src/test/java/uz/greenwhite/gateway/admin/SettingsControllerTest.java`

**Interfaces:**
- Consumes: `SettingsService` (get/set/markRestartPending/isRestartPending), `SettingsRegistry` (static), `Environment`.
- Produces:
  - `GET /qosid/admin/api/settings/registry` → `SettingsRegistryResponse { List<Item> settings; boolean restartPending }`, `Item { key, group, label, type, effectClass, value, secret }`.
  - `PUT /qosid/admin/api/settings/{key}` — read-only kalit → `400`; `REQUIRES_RESTART` → `markRestartPending()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/uz/greenwhite/gateway/admin/SettingsControllerTest.java`:
```java
package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import uz.greenwhite.gateway.admin.dto.SettingUpdateRequest;
import uz.greenwhite.gateway.admin.dto.SettingsRegistryResponse;
import uz.greenwhite.gateway.settings.SettingsService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock SettingsService settings;

    private SettingsController controller() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("server.port", "8090");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "x"));
        return new SettingsController(settings, null, env);
    }

    @Test
    void registryReturnsMetadataValuesAndPendingFlag() {
        when(settings.get(eq("gateway.api.base-url"), any())).thenReturn("https://live.example.com");
        when(settings.get(startsWith("gateway."), any())).thenReturn("x");
        when(settings.get(startsWith("spring."), any())).thenReturn("x");
        when(settings.get(eq("server.port"), any())).thenReturn("8090");
        when(settings.get(eq("management.server.port"), any())).thenReturn("8091");
        when(settings.isRestartPending()).thenReturn(true);

        SettingsRegistryResponse r = controller().registry().getBody();

        assertTrue(r.restartPending());
        assertTrue(r.settings().stream().anyMatch(i ->
                i.key().equals("gateway.api.base-url")
                && i.value().equals("https://live.example.com")
                && i.effectClass().equals("LIVE")));
        assertTrue(r.settings().stream().anyMatch(i ->
                i.key().equals("server.port") && i.effectClass().equals("READ_ONLY")));
    }

    @Test
    void putRejectsReadOnlyKey() {
        SettingUpdateRequest req = new SettingUpdateRequest();
        req.setValue("9999");
        assertThrows(IllegalArgumentException.class,
                () -> controller().update("server.port", req));
        verify(settings, never()).set(eq("server.port"), any(), anyBoolean(), any());
    }

    @Test
    void putRequiresRestartKeySetsPendingFlag() {
        SettingUpdateRequest req = new SettingUpdateRequest();
        req.setValue("5");
        controller().update("gateway.retry.max-attempts", req);
        verify(settings).set("gateway.retry.max-attempts", "5", false, "admin");
        verify(settings).markRestartPending();
    }

    @Test
    void putLiveKeyDoesNotSetPendingFlag() {
        SettingUpdateRequest req = new SettingUpdateRequest();
        req.setValue("https://new.example.com");
        controller().update("gateway.api.base-url", req);
        verify(settings).set("gateway.api.base-url", "https://new.example.com", false, "admin");
        verify(settings, never()).markRestartPending();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=SettingsControllerTest`
Expected: FAIL — `registry()` metodi va 3-argli konstruktor yo'q, `SettingsRegistryResponse` yo'q.

- [ ] **Step 3: Create the response DTO**

`src/main/java/uz/greenwhite/gateway/admin/dto/SettingsRegistryResponse.java`:
```java
package uz.greenwhite.gateway.admin.dto;

import java.util.List;

public record SettingsRegistryResponse(List<Item> settings, boolean restartPending) {
    public record Item(String key, String group, String label, String type,
                       String effectClass, String value, boolean secret) {}
}
```

- [ ] **Step 4: Modify SettingsController**

`src/main/java/uz/greenwhite/gateway/admin/SettingsController.java` — to'liq yangi mazmun:
```java
package uz.greenwhite.gateway.admin;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.SettingUpdateRequest;
import uz.greenwhite.gateway.admin.dto.SettingsRegistryResponse;
import uz.greenwhite.gateway.settings.SettingsRecord;
import uz.greenwhite.gateway.settings.SettingsRegistry;
import uz.greenwhite.gateway.settings.SettingsRegistry.EffectClass;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/qosid/admin/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public SettingsController(SettingsService settingsService,
                              @Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc,
                              Environment environment) {
        this.settingsService = settingsService;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    /** Registry-driven view: each known setting with metadata, current value, effect-class. */
    @GetMapping("/registry")
    public ResponseEntity<SettingsRegistryResponse> registry() {
        List<SettingsRegistryResponse.Item> items = SettingsRegistry.all().stream()
                .map(m -> {
                    String current = settingsService.get(m.key(), environment.getProperty(m.key()));
                    String display = m.secret() ? "***" : current;
                    return new SettingsRegistryResponse.Item(
                            m.key(), m.group(), m.label(), m.type(),
                            m.effectClass().name(), display, m.secret());
                })
                .toList();
        return ResponseEntity.ok(new SettingsRegistryResponse(items, settingsService.isRestartPending()));
    }

    @GetMapping
    public ResponseEntity<List<SettingsRecord>> getAll() {
        return ResponseEntity.ok(settingsService.getAllMasked());
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> update(@PathVariable String key, @RequestBody SettingUpdateRequest req) {
        if (SettingsRegistry.isReadOnly(key)) {
            throw new IllegalArgumentException("Setting '" + key + "' is read-only and cannot be changed");
        }
        if ("gateway.tenant.field-name".equals(key)
                && (req.getValue() == null || req.getValue().isBlank())) {
            throw new IllegalArgumentException("gateway.tenant.field-name must not be blank");
        }
        if ("gateway.tenant.default-rest-tenant".equals(key)) {
            try { Long.parseLong(req.getValue().trim()); }
            catch (Exception e) { throw new IllegalArgumentException("default-rest-tenant must be numeric"); }
        }
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        settingsService.set(key, req.getValue(), req.isSecret(), admin);
        if (SettingsRegistry.effectClassOf(key) == EffectClass.REQUIRES_RESTART) {
            settingsService.markRestartPending();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT skey AS \"skey\", old_value AS \"old_value\", new_value AS \"new_value\", " +
                "changed_by AS \"changed_by\", changed_at AS \"changed_at\" " +
                "FROM settings_audit ORDER BY changed_at DESC FETCH FIRST 200 ROWS ONLY"));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=SettingsControllerTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/admin/SettingsController.java src/main/java/uz/greenwhite/gateway/admin/dto/SettingsRegistryResponse.java src/test/java/uz/greenwhite/gateway/admin/SettingsControllerTest.java
git commit -m "feat: settings /registry endpoint + read-only guard + restart-pending on requires-restart edits"
```

---

### Task 5: Jonli o'qish — ProxyController + BiruniClient endpoint'lari

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/api/ProxyController.java:198`
- Modify: `src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java`
- Test: `src/test/java/uz/greenwhite/gateway/oracle/BiruniClientLiveEndpointTest.java`

**Interfaces:**
- Consumes: `SettingsService.get(key, default)`; `ProxyController` da `settingsService` allaqachon inject qilingan (maydon `settingsService`, ProxyController.java:67).
- Produces: endpoint'lar runtime'da DB'dan (bo'lsa) o'qiladi, aks holda yaml default.

**Muhim fakt:** `BiruniClient` da `SettingsService` HALI inject qilinmagan — qo'shiladi. `RestClient` `baseUrl` konstruktorda o'rnatiladi (BiruniClient.java:47) — uni olib tashlab, har chaqiruvда absolyut URL quriladi.

- [ ] **Step 1: ProxyController — api.base-url'ni jonli o'qish**

`src/main/java/uz/greenwhite/gateway/api/ProxyController.java` — 198-qatorni almashtiring:
```java
// eski:
//     .baseUrl(apiProperties.getBaseUrl())
// yangi:
                .baseUrl(settingsService.get("gateway.api.base-url", apiProperties.getBaseUrl()))
```

- [ ] **Step 2: BiruniClient — SettingsService inject + jonli endpoint**

`src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java`:

(a) Import qo'shing (fayl boshida boshqa importlar yoniga):
```java
import uz.greenwhite.gateway.settings.SettingsService;
```

(b) Maydon qo'shing (mavjud `private final` maydonlar yoniga, 29-32 atrofida):
```java
    private final SettingsService settingsService;
```
> `@RequiredArgsConstructor` (Lombok) bo'lsa konstruktor avtomatik yangilanadi. Agar qo'lda konstruktor bo'lsa, unga `SettingsService settingsService` parametrini qo'shib, `this.settingsService = settingsService;` yozing.

(c) `RestClient.builder()` dan `.baseUrl(properties.getBaseUrl())` (47-qator) ni **olib tashlang** — base-url endi har chaqiruvda quriladi. Timeout sozlamalari (41,44) o'z joyida qoladi.

(d) Pull chaqiruvi (75-qator atrofida) — absolyut URL:
```java
        String base = settingsService.get("gateway.source.base-url", properties.getBaseUrl());
        String pullUri = settingsService.get("gateway.source.request-pull-uri", properties.getRequestPullUri());
        // ... restClient.get().uri(base + pullUri) ...
```
71-qatordagi debug log ham `base`/`pullUri` lokal o'zgaruvchilaridan foydalansin.

(e) Save chaqiruvi (120-qator atrofida) — absolyut URL:
```java
        String saveBase = settingsService.get("gateway.source.base-url", properties.getBaseUrl());
        String saveUri = settingsService.get("gateway.source.response-save-uri", properties.getResponseSaveUri());
        // ... restClient.post().uri(saveBase + saveUri) ...
```

- [ ] **Step 3: Write the test**

`src/test/java/uz/greenwhite/gateway/oracle/BiruniClientLiveEndpointTest.java`:
```java
package uz.greenwhite.gateway.oracle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the source pull leg targets the LIVE base-url + pull-uri from SettingsService,
 * not the startup-bound GatewayProperties value.
 */
@ExtendWith(MockitoExtension.class)
class BiruniClientLiveEndpointTest {

    @Mock SettingsService settings;
    @Mock GatewayProperties props;
    // Other collaborators (TenantMappingService, SourceAuthService) mocked as needed by the ctor.

    @Test
    void pullUsesLiveBaseUrlAndUri() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://live-source.example.com/api/pull"))
              .andRespond(withSuccess("[]", org.springframework.http.MediaType.APPLICATION_JSON));

        when(settings.get(eq("gateway.source.base-url"), any())).thenReturn("https://live-source.example.com");
        when(settings.get(eq("gateway.source.request-pull-uri"), any())).thenReturn("/api/pull");
        // ... construct BiruniClient with builder.build(), props, mocks, settings ...
        // ... invoke the pull method ...

        server.verify();
    }
}
```
> **Eslatma:** Aniq konstruktor imzosi va pull-metod nomi `BiruniClient`'dan olinadi (implementer faylni o'qib to'ldiradi — mock kollaboratorlar ro'yxati va metod nomi shu yerda aniqlanadi). Test maqsadi: pull LIVE `base+uri` ga boradi.

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=BiruniClientLiveEndpointTest`
Expected: PASS. So'ng regressiya: `mvn -q test -Dtest=BiruniClient*Test` (mavjud BiruniClient testlari yashil).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/api/ProxyController.java src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java src/test/java/uz/greenwhite/gateway/oracle/BiruniClientLiveEndpointTest.java
git commit -m "feat: live endpoint reads — api.base-url (ProxyController) + source base/pull/save (BiruniClient)"
```

---

### Task 6: Frontend — API + reyestr-boshqariladigan color-coded sozlamalar sahifasi

**Files:**
- Modify: `frontend/src/api/settings.ts`
- Create: `frontend/src/pages/settings/GeneralSettingsPage.tsx`
- Modify: `frontend/src/pages/settings/SettingsPage.tsx`
- Modify: `frontend/src/locales/uz.json`
- Test: `frontend/src/pages/settings/GeneralSettingsPage.test.tsx`

**Interfaces:**
- Consumes: `GET /qosid/admin/api/settings/registry` → `{ settings: RegistryItem[], restartPending: boolean }`; `putSetting(key, value, secret)` (mavjud).
- Produces: `getRegistry()` API; `GeneralSettingsPage` komponenti.

- [ ] **Step 1: Add API types + getRegistry**

`frontend/src/api/settings.ts` — qo'shing (mavjud `getAudit` dan keyin):
```typescript
export interface RegistryItem {
  key: string; group: string; label: string; type: string;
  effectClass: 'LIVE' | 'REQUIRES_RESTART' | 'READ_ONLY';
  value: string; secret: boolean;
}
export interface SettingsRegistry { settings: RegistryItem[]; restartPending: boolean; }

export const getRegistry = () =>
  api.get<SettingsRegistry>(`${BASE}/settings/registry`).then(r => r.data);
```

- [ ] **Step 2: Write the failing test**

`frontend/src/pages/settings/GeneralSettingsPage.test.tsx`:
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { GeneralSettingsPage } from './GeneralSettingsPage';
import * as settingsApi from '../../api/settings';

beforeEach(() => vi.restoreAllMocks());

const registry = {
  restartPending: false,
  settings: [
    { key: 'gateway.api.base-url', group: 'Endpointlar', label: 'API proxy manzili', type: 'url', effectClass: 'LIVE', value: 'https://a.example.com', secret: false },
    { key: 'gateway.retry.max-attempts', group: 'Retry/HTTP', label: 'Max attempts', type: 'number', effectClass: 'REQUIRES_RESTART', value: '3', secret: false },
    { key: 'server.port', group: 'Infratuzilma', label: 'App port', type: 'number', effectClass: 'READ_ONLY', value: '8090', secret: false },
  ],
};

describe('GeneralSettingsPage', () => {
  it('renders grouped settings with effect-class badges', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    expect(screen.getByDisplayValue('https://a.example.com')).toBeInTheDocument();
    // read-only field is disabled
    expect(screen.getByDisplayValue('8090')).toBeDisabled();
  });

  it('saves a live setting via putSetting', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as any);
    const put = vi.spyOn(settingsApi, 'putSetting').mockResolvedValue({} as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText('API proxy manzili')).toBeInTheDocument());
    const input = screen.getByDisplayValue('https://a.example.com');
    await userEvent.clear(input);
    await userEvent.type(input, 'https://b.example.com');
    await userEvent.click(screen.getAllByRole('button', { name: /Saqlash/ })[0]);
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.api.base-url', 'https://b.example.com', false));
  });

  it('shows the restart-pending banner when flag is set', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue({ ...registry, restartPending: true } as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText(/restart/i)).toBeInTheDocument());
  });

  it('does not allow editing a read-only field save', async () => {
    vi.spyOn(settingsApi, 'getRegistry').mockResolvedValue(registry as any);
    render(<GeneralSettingsPage />);
    await waitFor(() => expect(screen.getByText('App port')).toBeInTheDocument());
    // read-only row renders a lock, no Saqlash button
    const portRow = screen.getByText('App port').closest('div')!;
    expect(portRow.textContent).toMatch(/🔒|lock/i);
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/GeneralSettingsPage.test.tsx`
Expected: FAIL — `GeneralSettingsPage` yo'q.

- [ ] **Step 4: Implement GeneralSettingsPage**

`frontend/src/pages/settings/GeneralSettingsPage.tsx`:
```typescript
import { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Collapse, Input, Button, Tag, Space, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getRegistry, putSetting, RegistryItem } from '../../api/settings';

const BADGE: Record<RegistryItem['effectClass'], { color: string; key: string }> = {
  LIVE: { color: 'green', key: 'settings.legend.live' },
  REQUIRES_RESTART: { color: 'gold', key: 'settings.legend.restart' },
  READ_ONLY: { color: 'default', key: 'settings.legend.readonly' },
};

export function GeneralSettingsPage() {
  const { t } = useTranslation();
  const [items, setItems] = useState<RegistryItem[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [restartPending, setRestartPending] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setLoadError(null);
    getRegistry()
      .then((r) => {
        setItems(r.settings);
        setRestartPending(r.restartPending);
        setDrafts(Object.fromEntries(r.settings.map((i) => [i.key, i.value])));
      })
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const groups = useMemo(() => {
    const map = new Map<string, RegistryItem[]>();
    for (const it of items) (map.get(it.group) ?? map.set(it.group, []).get(it.group)!).push(it);
    return [...map.entries()];
  }, [items]);

  const save = async (it: RegistryItem) => {
    try {
      await putSetting(it.key, drafts[it.key] ?? '', it.secret);
      if (it.effectClass === 'REQUIRES_RESTART') {
        setRestartPending(true);
        message.warning(t('settings.savedRestart'));
      } else {
        message.success(t('settings.savedLive'));
      }
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
    }
  };

  return (
    <Card loading={loading}>
      {loadError && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={loadError} />}
      <Space style={{ marginBottom: 12 }} size="middle">
        <Tag color="green">{t('settings.legend.live')}</Tag>
        <Tag color="gold">{t('settings.legend.restart')}</Tag>
        <Tag>{t('settings.legend.readonly')}</Tag>
      </Space>
      {restartPending && (
        <Alert style={{ marginBottom: 16 }} type="warning" showIcon
               message={t('settings.restartPending')} />
      )}
      <Collapse defaultActiveKey={groups.map(([g]) => g)} items={groups.map(([group, rows]) => ({
        key: group,
        label: group,
        children: (
          <div>
            {rows.map((it) => {
              const readOnly = it.effectClass === 'READ_ONLY';
              return (
                <div key={it.key} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                  <Tag color={BADGE[it.effectClass].color} style={{ minWidth: 90, textAlign: 'center' }}>
                    {t(BADGE[it.effectClass].key)}
                  </Tag>
                  <span style={{ minWidth: 200 }}>{it.label}</span>
                  <Input
                    style={{ maxWidth: 360 }}
                    aria-label={it.label}
                    value={drafts[it.key] ?? ''}
                    disabled={readOnly}
                    onChange={(e) => setDrafts({ ...drafts, [it.key]: e.target.value })}
                    suffix={readOnly ? <LockOutlined /> : undefined}
                  />
                  {!readOnly && (
                    <Button size="small" type="primary" onClick={() => save(it)}>
                      {t('settings.save')}
                    </Button>
                  )}
                </div>
              );
            })}
          </div>
        ),
      }))} />
    </Card>
  );
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/GeneralSettingsPage.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/settings.ts frontend/src/pages/settings/GeneralSettingsPage.tsx frontend/src/pages/settings/GeneralSettingsPage.test.tsx
git commit -m "feat: registry-driven color-coded GeneralSettingsPage + getRegistry API"
```

---

### Task 7: SettingsPage wiring + i18n + audit ko'chirish + docs

**Files:**
- Modify: `frontend/src/pages/settings/SettingsPage.tsx`
- Modify: `frontend/src/locales/uz.json`
- Modify: `ai-context/CONFIGURATION.md`, `ai-context/ARCHITECTURE.md`, `CLAUDE.md`
- Delete: `frontend/src/pages/settings/AdvancedSettingsPage.tsx` + `.test.tsx` (o'rnini GeneralSettingsPage oladi) — **audit tab'ini** GeneralSettingsPage yoki alohida saqlab qoling (pastga qarang).

**Interfaces:**
- Consumes: `GeneralSettingsPage` (Task 6), mavjud `getAudit` (audit tab uchun).

- [ ] **Step 1: Audit tab'ini saqlash**

`AdvancedSettingsPage` ichidagi **audit** ko'rinishi (getAudit + auditColumns, AdvancedSettingsPage.tsx:75-81, 88-89) `GeneralSettingsPage`ga `Tabs` sifatida ko'chiring (Settings tab + Audit tab), yoki alohida `AuditPage.tsx` yarating. YAGNI: `GeneralSettingsPage` ichida ikki tab — "Sozlamalar" (reyestr) + "Audit" (mavjud jadval). Audit render kodini AdvancedSettingsPage'dan ko'chiring (o'zgarmagan).

- [ ] **Step 2: i18n kalitlarini qo'shish**

`frontend/src/locales/uz.json` — `settings` obyekti ichiga qo'shing (mavjud kalitlarni buzmasdan):
```json
"legend": { "live": "Jonli", "restart": "Restart kerak", "readonly": "Bloklangan" },
"restartPending": "Ba'zi sozlamalar o'zgardi — qo'llash uchun gateway'ni qayta ishga tushiring (Ops konsoli).",
"savedLive": "Saqlandi va qo'llandi",
"savedRestart": "Saqlandi — qo'llash uchun restart kerak",
"general": "Sozlamalar"
```

- [ ] **Step 3: SettingsPage'da tab'ni almashtirish**

`frontend/src/pages/settings/SettingsPage.tsx` — `AdvancedSettingsPage` importini `GeneralSettingsPage`ga almashtiring va `advanced` tab children'ini `<GeneralSettingsPage />` qiling; tab label `t('settings.general')`:
```typescript
import { GeneralSettingsPage } from './GeneralSettingsPage';
// ...
{ key: 'advanced', label: t('settings.general'), children: <GeneralSettingsPage /> },
```

- [ ] **Step 4: Eski faylni o'chirish**

```bash
git rm frontend/src/pages/settings/AdvancedSettingsPage.tsx frontend/src/pages/settings/AdvancedSettingsPage.test.tsx
```

- [ ] **Step 5: Butun frontend test to'plamini yugurtirish**

Run: `cd frontend && npx vitest run`
Expected: PASS — barcha testlar yashil (yangi GeneralSettingsPage testlari + qolganlar; AdvancedSettingsPage testlari o'chirilgan).

- [ ] **Step 6: Docs yangilash**

- `ai-context/CONFIGURATION.md`: `gateway.*` kalitlar jadvaliga effect-class ustuni; `gateway.source.base-url`/pull/save endi UI'da jonli tahrirlanadi; read-only bootstrap kalitlar ro'yxati.
- `ai-context/ARCHITECTURE.md`: "Settings control-plane" bo'limiga `SettingsEnvironmentPostProcessor` (boot-vaqt DB→Environment) + `SettingsRegistry` + restart-pending oqimi.
- `CLAUDE.md`: REST API jadvalidagi `/qosid/admin/api/settings` qatoriga `/registry` (effect-class + restart-pending) qo'shing.

- [ ] **Step 7: Butun backend test to'plami (regressiya) + commit**

Run: `mvn -q test`
Expected: PASS — barcha backend testlar yashil (pipeline E1-E5 tegilmagan).

```bash
git add frontend/src/pages/settings/SettingsPage.tsx frontend/src/locales/uz.json ai-context/CONFIGURATION.md ai-context/ARCHITECTURE.md CLAUDE.md
git commit -m "feat: wire GeneralSettingsPage into Settings tabs, i18n, docs; retire AdvancedSettingsPage"
```

---

## Self-Review (writing-plans)

**Spec coverage:**
- Spec §3 arxitektura (PropertySource / live-read / registry) → Task 1,2,5 ✅
- Spec §4 tasnif → Task 1 (SettingsRegistry) ✅
- Spec §5 restart-pending → Task 3 ✅
- Spec §6 API (registry GET, PUT validatsiya) → Task 4 ✅
- Spec §7 frontend (color-coded, legend, banner) → Task 6,7 ✅
- Spec §8 testlash → har task'da TDD ✅
- Spec §9 xavfsizlik (pipeline minimal tegish, read-only guard, never-fail) → Task 2,4,5 ✅

**Placeholder scan:** Task 5 (BiruniClient) test'ida konstruktor imzosi implementer tomonidan faylni o'qib to'ldiriladi — bu ataylab (mavjud kollaboratorlar ro'yxati faylga bog'liq); qolgan barcha kod to'liq.

**Type consistency:** `EffectClass` (LIVE/REQUIRES_RESTART/READ_ONLY) — Task 1 enum, Task 4 `.name()` String sifatida DTO'da, Task 6 frontend union type — mos. `getRegistry()` javob shakli (`{settings, restartPending}`) Task 4 DTO ↔ Task 6 interfeys — mos. `markRestartPending()`/`isRestartPending()` Task 3 ↔ Task 4 — mos.

## Execution Handoff

Plan `docs/superpowers/plans/2026-07-15-settings-editability.md` ga saqlandi.
