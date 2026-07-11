# B1: Settings Poydevori — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** qosid-flow'ga UI'dan boshqariladigan runtime sozlamalar poydevorini qo'shish: H2 embedded baza + SettingsService + admin login + `/qosid/admin/api`, pipeline yadrosiga tegmasdan.

**Architecture:** Yangi mustaqil "control-plane" qatlami. Oracle `DataSourceAutoConfiguration` o'chirilgani sabab, settings uchun alohida aniq H2 `DataSource`/`JdbcTemplate`. Spring Security ikki alohida `SecurityFilterChain` bilan qo'shiladi — admin API sessiya talab qiladi, pipeline (`/qosid/api/**`) va actuator ochiq qoladi. Secretlar: outbound credential AES, admin parol bcrypt (B1'da faqat admin parol bor).

**Tech Stack:** Java 21, Spring Boot 3.3.0, Spring Security, H2 2.x, JdbcTemplate, JUnit 5 + Mockito + MockMvc, Maven.

## Global Constraints

- Branch: `debug`da ishlanadi. `master`ga hech qachon push qilinmaydi.
- Base package `uz.greenwhite.gateway`. Yangi paketlar: `settings`, `admin`, config'lar `config`da. Lombok `@Getter/@Setter`, `@Slf4j`, `@RequiredArgsConstructor` uslubi.
- Pipeline yadrosi (consumer, CB, RocksDB, Redis state, `/qosid/api/**`, actuator 8091) B1'da O'ZGARMAYDI.
- **Maven PATH'da YO'Q.** Test buyrug'i (PowerShell): `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test` (fokus uchun `-Dtest=ClassName` qo'shiladi). Har task oxirida to'liq suite yashil bo'lishi shart.
- H2 fayl yo'li: `./data/settings.mv.db` (`SETTINGS_DB_PATH` env bilan override). AES master kalit: `SETTINGS_ENCRYPTION_KEY` env. Admin reset: `ADMIN_RESET`/`ADMIN_PASSWORD_RESET` env.
- Admin API prefiksi: `/qosid/admin/api`. Xatolar uniform `ErrorResponse` (mavjud klass: `error`, `message`, `status`, `timestamp`).
- Har task TDD: failing test → verify RED → implement → verify GREEN → commit.

## Fayl xaritasi

- `pom.xml` — H2 + spring-boot-starter-security dependency.
- `src/main/resources/settings-schema.sql` — 3 jadval (`CREATE TABLE IF NOT EXISTS`).
- `config/SettingsDbConfig.java` — alohida H2 `DataSource` + `JdbcTemplate` + schema init.
- `config/SettingsProperties.java` — H2 yo'li, AES kalit, sessiya TTL (`gateway.settings.*`).
- `settings/SettingsCipher.java` — AES encrypt/decrypt.
- `settings/SettingsRecord.java` — bitta sozlama qatori (DTO).
- `settings/SettingsRepository.java` — JdbcTemplate CRUD + audit insert.
- `settings/SettingsService.java` — kesh + get/set/getSecret + audit + masking.
- `settings/SettingsMigrationRunner.java` — yaml→DB birinchi startup migratsiya.
- `admin/AdminAccount.java`, `admin/AdminAccountRepository.java` — admin yozuvi + seed/reset.
- `admin/AdminAuthService.java` — bcrypt login, change-password, rate-limit.
- `admin/LoginRateLimiter.java` — in-memory rate-limit.
- `config/AdminSecurityConfig.java` — ikki `SecurityFilterChain` + `PasswordEncoder`.
- `admin/AdminAuthController.java`, `admin/SettingsController.java` — admin API.
- `admin/dto/*.java` — request/response DTO'lar.

---

### Task 1: H2 dependency + SettingsDbConfig (alohida DataSource + schema)

**Files:**
- Modify: `pom.xml` (dependencies bloki)
- Create: `src/main/resources/settings-schema.sql`
- Create: `src/main/java/uz/greenwhite/gateway/config/SettingsProperties.java`
- Create: `src/main/java/uz/greenwhite/gateway/config/SettingsDbConfig.java`
- Test: `src/test/java/uz/greenwhite/gateway/config/SettingsDbConfigTest.java`

**Interfaces:**
- Produces: bean `@Qualifier("settingsJdbcTemplate") JdbcTemplate` — settings jadvallariga ishlaydigan JdbcTemplate. `SettingsProperties` (getters: `getDbPath():String`, `getEncryptionKey():String`, `getSessionTtlMinutes():long`).

- [ ] **Step 1: Add H2 dependency**

`pom.xml` dependencies blokiga (ojdbc11'dan keyin) qo'shing:

```xml
        <!-- H2 — embedded settings DB (control-plane), file mode -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <version>2.2.224</version>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

```java
package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class SettingsDbConfigTest {

    @Test
    void createsSchemaAndTablesAreQueryable() {
        SettingsProperties props = new SettingsProperties();
        // in-memory H2 for the test, named so tables persist for the connection
        props.setDbPath("mem:settings_test_task1;DB_CLOSE_DELAY=-1");

        SettingsDbConfig config = new SettingsDbConfig();
        DataSource ds = config.settingsDataSource(props);
        JdbcTemplate jdbc = config.settingsJdbcTemplate(ds);
        config.initSchema(ds);

        // All three tables exist and are empty
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM settings", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM settings_audit", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_account", Integer.class));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `$env:JAVA_HOME="C:\Program Files\Java\jdk-21"; & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test -q -Dtest=SettingsDbConfigTest`
Expected: FAIL — compile error (`SettingsProperties`, `SettingsDbConfig` mavjud emas).

- [ ] **Step 4: Create SettingsProperties**

```java
package uz.greenwhite.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Control-plane (settings DB + admin) bootstrap config. Lives in yaml, NOT in the settings DB. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.settings")
public class SettingsProperties {

    /** H2 path/URL fragment. Default file mode; tests override with mem:. */
    private String dbPath = "./data/settings";

    /** AES master key (base64 or raw). Blank in dev → secrets disabled. */
    private String encryptionKey = "";

    /** Admin session TTL in minutes. */
    private long sessionTtlMinutes = 480;
}
```

- [ ] **Step 5: Create settings-schema.sql**

`src/main/resources/settings-schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS settings (
    skey        VARCHAR(200) PRIMARY KEY,
    svalue      CLOB,
    secret      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS settings_audit (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    skey        VARCHAR(200) NOT NULL,
    old_value   CLOB,
    new_value   CLOB,
    changed_by  VARCHAR(100),
    changed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS admin_account (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP
);
```

- [ ] **Step 6: Create SettingsDbConfig**

```java
package uz.greenwhite.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Dedicated H2 DataSource for the control-plane settings DB.
 * Independent of the excluded Oracle DataSourceAutoConfiguration — this is the
 * only DataSource in the app and is used solely by the settings/admin layer.
 */
@Slf4j
@Configuration
public class SettingsDbConfig {

    @Bean
    public DataSource settingsDataSource(SettingsProperties props) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:" + props.getDbPath() + ";MODE=LEGACY");
        ds.setUsername("sa");
        ds.setPassword("");
        // Init schema HERE, inside the DataSource bean, so it exists before ANY
        // consumer (SettingsService @PostConstruct, repositories) touches the DB.
        // A separate init bean has undefined ordering vs SettingsService — bug.
        initSchema(ds);
        return ds;
    }

    @Bean
    @Qualifier("settingsJdbcTemplate")
    public JdbcTemplate settingsJdbcTemplate(DataSource settingsDataSource) {
        return new JdbcTemplate(settingsDataSource);
    }

    /** Runs settings-schema.sql (idempotent CREATE IF NOT EXISTS). Public for tests. */
    public void initSchema(DataSource ds) {
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("settings-schema.sql"));
            log.info("Settings DB schema initialized");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize settings DB schema", e);
        }
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `... mvn.cmd test -q -Dtest=SettingsDbConfigTest`
Expected: PASS.

- [ ] **Step 8: Run full suite + commit**

Run: `... mvn.cmd test` → expect 29 tests (28 + 1 new), 0 failures.

```bash
git add pom.xml src/main/resources/settings-schema.sql src/main/java/uz/greenwhite/gateway/config/SettingsProperties.java src/main/java/uz/greenwhite/gateway/config/SettingsDbConfig.java src/test/java/uz/greenwhite/gateway/config/SettingsDbConfigTest.java
git commit -m "feat(settings): H2 control-plane DataSource + schema (settings, audit, admin_account)"
```

---

### Task 2: SettingsCipher (AES encrypt/decrypt)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/settings/SettingsCipher.java`
- Test: `src/test/java/uz/greenwhite/gateway/settings/SettingsCipherTest.java`

**Interfaces:**
- Produces: `SettingsCipher` bean. `encrypt(String plain):String` (base64 ciphertext), `decrypt(String cipher):String`, `isEnabled():boolean` (false when key blank). Constructed from `SettingsProperties`.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.SettingsProperties;

import static org.junit.jupiter.api.Assertions.*;

class SettingsCipherTest {

    private SettingsCipher withKey(String key) {
        SettingsProperties props = new SettingsProperties();
        props.setEncryptionKey(key);
        return new SettingsCipher(props);
    }

    @Test
    void roundTripReturnsOriginal() {
        SettingsCipher cipher = withKey("0123456789abcdef0123456789abcdef");
        String secret = "client-secret-xyz";
        String enc = cipher.encrypt(secret);
        assertNotEquals(secret, enc);
        assertEquals(secret, cipher.decrypt(enc));
        assertTrue(cipher.isEnabled());
    }

    @Test
    void sameInputProducesDifferentCiphertext() {
        SettingsCipher cipher = withKey("0123456789abcdef0123456789abcdef");
        assertNotEquals(cipher.encrypt("x"), cipher.encrypt("x")); // random IV
    }

    @Test
    void blankKeyDisablesCipher() {
        SettingsCipher cipher = withKey("");
        assertFalse(cipher.isEnabled());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SettingsCipherTest`
Expected: FAIL — `SettingsCipher` mavjud emas.

- [ ] **Step 3: Implement SettingsCipher**

```java
package uz.greenwhite.gateway.settings;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.config.SettingsProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM encryption for at-rest secrets in the settings DB. Key derived from
 * {@code gateway.settings.encryption-key} via SHA-256 (so any-length key works).
 * Blank key → {@link #isEnabled()} false; callers must not store secrets then.
 */
@Slf4j
@Component
public class SettingsCipher {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKeySpec keySpec;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public SettingsCipher(SettingsProperties props) {
        String key = props.getEncryptionKey();
        this.enabled = key != null && !key.isBlank();
        if (enabled) {
            this.keySpec = new SecretKeySpec(sha256(key), "AES");
            log.info("Settings encryption ENABLED (AES-GCM)");
        } else {
            this.keySpec = null;
            log.warn("Settings encryption DISABLED: gateway.settings.encryption-key is blank — secrets cannot be stored");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encrypt(String plain) {
        requireEnabled();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Settings encrypt failed", e);
        }
    }

    public String decrypt(String cipherText) {
        requireEnabled();
        try {
            byte[] in = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(in, 0, iv, 0, GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(in, GCM_IV_LENGTH, in.length - GCM_IV_LENGTH);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Settings decrypt failed", e);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Settings encryption disabled: set gateway.settings.encryption-key");
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run test + full suite + commit**

Run: `... mvn.cmd test` → 32 tests (29 + 3), 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/settings/SettingsCipher.java src/test/java/uz/greenwhite/gateway/settings/SettingsCipherTest.java
git commit -m "feat(settings): AES-GCM cipher for at-rest secrets"
```

---

### Task 3: SettingsRepository + SettingsRecord (JdbcTemplate CRUD)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/settings/SettingsRecord.java`
- Create: `src/main/java/uz/greenwhite/gateway/settings/SettingsRepository.java`
- Test: `src/test/java/uz/greenwhite/gateway/settings/SettingsRepositoryTest.java`

**Interfaces:**
- Consumes: `@Qualifier("settingsJdbcTemplate") JdbcTemplate` (Task 1).
- Produces: `SettingsRecord` (fields: `key`, `value`, `secret`, `updatedAt`, `updatedBy`). `SettingsRepository`: `findAll():List<SettingsRecord>`, `find(key):Optional<SettingsRecord>`, `upsert(key, value, secret, changedBy)`, `insertAudit(key, oldValue, newValue, changedBy)`.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uz.greenwhite.gateway.config.SettingsDbConfig;
import uz.greenwhite.gateway.config.SettingsProperties;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SettingsRepositoryTest {

    private SettingsRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        SettingsProperties props = new SettingsProperties();
        props.setDbPath("mem:settings_test_repo_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        SettingsDbConfig config = new SettingsDbConfig();
        DriverManagerDataSource ds = (DriverManagerDataSource) config.settingsDataSource(props);
        config.initSchema(ds);
        jdbc = config.settingsJdbcTemplate(ds);
        repo = new SettingsRepository(jdbc);
    }

    @Test
    void upsertInsertsThenUpdates() {
        repo.upsert("gateway.retry.max-attempts", "3", false, "admin");
        Optional<SettingsRecord> r = repo.find("gateway.retry.max-attempts");
        assertTrue(r.isPresent());
        assertEquals("3", r.get().getValue());
        assertFalse(r.get().isSecret());

        repo.upsert("gateway.retry.max-attempts", "5", false, "admin");
        assertEquals("5", repo.find("gateway.retry.max-attempts").get().getValue());
        assertEquals(1, repo.findAll().size()); // still one row
    }

    @Test
    void insertAuditAppendsRow() {
        repo.insertAudit("k", "old", "new", "admin");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM settings_audit WHERE skey='k'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void findMissingReturnsEmpty() {
        assertTrue(repo.find("nope").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SettingsRepositoryTest`
Expected: FAIL — `SettingsRecord`/`SettingsRepository` mavjud emas.

- [ ] **Step 3: Create SettingsRecord**

```java
package uz.greenwhite.gateway.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class SettingsRecord {
    private String key;
    private String value;
    private boolean secret;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
```

- [ ] **Step 4: Create SettingsRepository**

```java
package uz.greenwhite.gateway.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SettingsRepository {

    private final @Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc;

    private static final RowMapper<SettingsRecord> MAPPER = (rs, n) -> SettingsRecord.builder()
            .key(rs.getString("skey"))
            .value(rs.getString("svalue"))
            .secret(rs.getBoolean("secret"))
            .updatedAt(rs.getObject("updated_at", Timestamp.class) != null
                    ? rs.getTimestamp("updated_at").toLocalDateTime() : null)
            .updatedBy(rs.getString("updated_by"))
            .build();

    public List<SettingsRecord> findAll() {
        return jdbc.query("SELECT * FROM settings ORDER BY skey", MAPPER);
    }

    public Optional<SettingsRecord> find(String key) {
        return jdbc.query("SELECT * FROM settings WHERE skey = ?", MAPPER, key).stream().findFirst();
    }

    public void upsert(String key, String value, boolean secret, String changedBy) {
        jdbc.update(
                "MERGE INTO settings (skey, svalue, secret, updated_at, updated_by) " +
                "KEY (skey) VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)",
                key, value, secret, changedBy);
    }

    public void insertAudit(String key, String oldValue, String newValue, String changedBy) {
        jdbc.update(
                "INSERT INTO settings_audit (skey, old_value, new_value, changed_by) VALUES (?, ?, ?, ?)",
                key, oldValue, newValue, changedBy);
    }
}
```

- [ ] **Step 5: Run test + full suite + commit**

Run: `... mvn.cmd test` → 35 tests, 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/settings/SettingsRecord.java src/main/java/uz/greenwhite/gateway/settings/SettingsRepository.java src/test/java/uz/greenwhite/gateway/settings/SettingsRepositoryTest.java
git commit -m "feat(settings): SettingsRepository JdbcTemplate CRUD + audit"
```

---

### Task 4: SettingsService (kesh + get/set/getSecret + audit + masking)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/settings/SettingsService.java`
- Test: `src/test/java/uz/greenwhite/gateway/settings/SettingsServiceTest.java`

**Interfaces:**
- Consumes: `SettingsRepository` (Task 3), `SettingsCipher` (Task 2).
- Produces: `SettingsService` — `get(key, default):String`, `getSecret(key, default):String`, `set(key, value, secret, changedBy):void`, `getAllMasked():List<SettingsRecord>` (secret value → `"***"`), `reload():void` (kesh qayta yuklash). Startup'da `@PostConstruct` kesh yuklaydi.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock SettingsRepository repo;
    @Mock SettingsCipher cipher;

    private SettingsService service() {
        SettingsService s = new SettingsService(repo, cipher);
        s.reload();
        return s;
    }

    @Test
    void getReturnsCachedValueElseDefault() {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("a").value("1").secret(false).build()));
        SettingsService s = service();
        assertEquals("1", s.get("a", "def"));
        assertEquals("def", s.get("missing", "def"));
    }

    @Test
    void getSecretDecrypts() {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("pw").value("ENC").secret(true).build()));
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.decrypt("ENC")).thenReturn("plain");
        SettingsService s = service();
        assertEquals("plain", s.getSecret("pw", "def"));
    }

    @Test
    void setEncryptsSecretAndAudits() {
        when(repo.findAll()).thenReturn(List.of());
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.encrypt("plain")).thenReturn("ENC");
        SettingsService s = service();

        s.set("pw", "plain", true, "admin");

        verify(cipher).encrypt("plain");
        verify(repo).upsert("pw", "ENC", true, "admin");
        verify(repo).insertAudit(eq("pw"), any(), eq("***"), eq("admin")); // secret masked in audit
        assertEquals("plain", s.getSecret("pw", "x")); // cache updated with plaintext-readable path
    }

    @Test
    void getAllMasksSecrets() {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("pw").value("ENC").secret(true).build(),
                SettingsRecord.builder().key("a").value("1").secret(false).build()));
        SettingsService s = service();
        List<SettingsRecord> all = s.getAllMasked();
        assertEquals("***", all.stream().filter(r -> r.getKey().equals("pw")).findFirst().get().getValue());
        assertEquals("1", all.stream().filter(r -> r.getKey().equals("a")).findFirst().get().getValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SettingsServiceTest`
Expected: FAIL — `SettingsService` mavjud emas.

- [ ] **Step 3: Implement SettingsService**

```java
package uz.greenwhite.gateway.settings;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entry point for runtime settings. Cache-backed (one instance, no
 * distributed invalidation). Secret values are stored AES-encrypted; the cache
 * holds the encrypted form and decrypts on {@link #getSecret}. Audit masks secrets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final String MASK = "***";

    private final SettingsRepository repository;
    private final SettingsCipher cipher;

    /** key -> record (secret records hold encrypted value). */
    private final ConcurrentHashMap<String, SettingsRecord> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void reload() {
        cache.clear();
        for (SettingsRecord r : repository.findAll()) {
            cache.put(r.getKey(), r);
        }
        log.info("SettingsService cache loaded: {} entries", cache.size());
    }

    public String get(String key, String defaultValue) {
        SettingsRecord r = cache.get(key);
        return r != null && r.getValue() != null ? r.getValue() : defaultValue;
    }

    public String getSecret(String key, String defaultValue) {
        SettingsRecord r = cache.get(key);
        if (r == null || r.getValue() == null) return defaultValue;
        if (!r.isSecret()) return r.getValue();
        if (!cipher.isEnabled()) {
            log.warn("getSecret({}) but cipher disabled — returning default", key);
            return defaultValue;
        }
        return cipher.decrypt(r.getValue());
    }

    public void set(String key, String value, boolean secret, String changedBy) {
        SettingsRecord prev = cache.get(key);
        String stored = value;
        if (secret) {
            if (!cipher.isEnabled()) {
                throw new IllegalStateException("Cannot store secret '" + key + "': encryption disabled");
            }
            stored = cipher.encrypt(value);
        }
        repository.upsert(key, stored, secret, changedBy);
        String oldForAudit = prev == null ? null : (prev.isSecret() ? MASK : prev.getValue());
        String newForAudit = secret ? MASK : value;
        repository.insertAudit(key, oldForAudit, newForAudit, changedBy);
        cache.put(key, SettingsRecord.builder()
                .key(key).value(stored).secret(secret).updatedBy(changedBy).build());
    }

    public List<SettingsRecord> getAllMasked() {
        List<SettingsRecord> out = new ArrayList<>();
        for (SettingsRecord r : cache.values()) {
            out.add(SettingsRecord.builder()
                    .key(r.getKey())
                    .value(r.isSecret() ? MASK : r.getValue())
                    .secret(r.isSecret())
                    .updatedAt(r.getUpdatedAt())
                    .updatedBy(r.getUpdatedBy())
                    .build());
        }
        out.sort((a, b) -> a.getKey().compareTo(b.getKey()));
        return out;
    }
}
```

- [ ] **Step 4: Run test + full suite + commit**

Run: `... mvn.cmd test` → 39 tests, 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/settings/SettingsService.java src/test/java/uz/greenwhite/gateway/settings/SettingsServiceTest.java
git commit -m "feat(settings): SettingsService with cache, secret encrypt/decrypt, masked audit"
```

---

### Task 5: yaml→DB migration (birinchi startup)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/settings/SettingsMigrationRunner.java`
- Test: `src/test/java/uz/greenwhite/gateway/settings/SettingsMigrationRunnerTest.java`

**Interfaces:**
- Consumes: `SettingsRepository`, `SettingsService`, Spring `Environment`.
- Produces: `SettingsMigrationRunner implements ApplicationRunner` — birinchi startup'da (`settings` bo'sh) ko'chiriladigan kalitlar ro'yxatini yaml/Environment'dan o'qib DB'ga yozadi. `MIGRATABLE_KEYS` (kalit → yaml property) konstanta.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsMigrationRunnerTest {

    @Mock SettingsRepository repo;
    @Mock SettingsService service;
    @Mock Environment env;

    @Test
    void migratesWhenSettingsEmpty() throws Exception {
        when(repo.findAll()).thenReturn(List.of()); // empty → migrate
        when(env.getProperty("gateway.retry.max-attempts")).thenReturn("3");
        when(env.getProperty(anyString())).thenReturn(null);
        when(env.getProperty("gateway.retry.max-attempts")).thenReturn("3");

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service, atLeastOnce()).set(eq("gateway.retry.max-attempts"), eq("3"), eq(false), eq("migration"));
    }

    @Test
    void skipsWhenSettingsPopulated() throws Exception {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("x").value("1").build())); // non-empty → skip

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service, never()).set(anyString(), anyString(), anyBoolean(), anyString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SettingsMigrationRunnerTest`
Expected: FAIL — klass mavjud emas.

- [ ] **Step 3: Implement SettingsMigrationRunner**

```java
package uz.greenwhite.gateway.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On first boot (settings table empty), copies migratable yaml values into the
 * settings DB once. Bootstrap values (Kafka/Redis/ports/H2 path) are NOT migrated —
 * they stay in yaml. Idempotent: runs only when the table is empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsMigrationRunner implements ApplicationRunner {

    /** settings key -> yaml property name. Non-secret runtime settings only. */
    static final Map<String, String> MIGRATABLE_KEYS = new LinkedHashMap<>() {{
        put("gateway.api.base-url", "gateway.api.base-url");
        put("gateway.retry.max-attempts", "gateway.retry.max-attempts");
        put("gateway.retry.interval-ms", "gateway.retry.interval-ms");
        put("gateway.retry.retryable-statuses", "gateway.retry.retryable-statuses");
        put("gateway.retry.max-message-age-ms", "gateway.retry.max-message-age-ms");
        put("gateway.http.connect-timeout-ms", "gateway.http.connect-timeout-ms");
        put("gateway.http.read-timeout-ms", "gateway.http.read-timeout-ms");
        put("gateway.response.threshold-size", "gateway.response.threshold-size");
        put("gateway.polling.enabled", "gateway.polling.enabled");
        put("gateway.polling.interval-ms", "gateway.polling.interval-ms");
        put("gateway.tenant.field-name", "gateway.tenant.field-name");
        put("gateway.tenant.extra-field-name", "gateway.tenant.extra-field-name");
        put("gateway.tenant.default-rest-tenant", "gateway.tenant.default-rest-tenant");
    }};

    private final SettingsRepository repository;
    private final SettingsService settingsService;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (!repository.findAll().isEmpty()) {
            log.info("Settings DB already populated — skipping yaml→DB migration");
            return;
        }
        int migrated = 0;
        for (Map.Entry<String, String> e : MIGRATABLE_KEYS.entrySet()) {
            String value = environment.getProperty(e.getValue());
            if (value != null) {
                settingsService.set(e.getKey(), value, false, "migration");
                migrated++;
            }
        }
        log.info("yaml→DB settings migration complete: {} keys migrated", migrated);
    }
}
```

Note: `gateway.tenant.*` yaml defaults may not exist yet (added in C-faza). `environment.getProperty` returns null for absent keys and they're skipped — that's correct; C-faza/B2 will add them. To provide defaults now, add to `application.yml` under `gateway:` a `tenant:` block: `field-name: company_id`, `extra-field-name: filial_id`, `default-rest-tenant: 0`. Add that yaml block in this step.

- [ ] **Step 4: Add tenant defaults to application.yml**

In `src/main/resources/application.yml`, under `gateway:` (after the `proxy:` block):

```yaml
  # --- Tenant mapping (C-faza; field names configurable, default keeps bmb working) ---
  tenant:
    field-name: ${TENANT_FIELD:company_id}
    extra-field-name: ${EXTRA_TENANT_FIELD:filial_id}
    default-rest-tenant: ${DEFAULT_REST_TENANT:0}
```

- [ ] **Step 5: Run test + full suite + commit**

Run: `... mvn.cmd test` → 41 tests, 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/settings/SettingsMigrationRunner.java src/test/java/uz/greenwhite/gateway/settings/SettingsMigrationRunnerTest.java src/main/resources/application.yml
git commit -m "feat(settings): first-boot yaml→DB migration for runtime settings"
```

---

### Task 6: AdminAccount + repository + seed/reset

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/admin/AdminAccount.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/AdminAccountRepository.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/AdminAccountInitializer.java`
- Test: `src/test/java/uz/greenwhite/gateway/admin/AdminAccountRepositoryTest.java`

**Interfaces:**
- Consumes: `@Qualifier("settingsJdbcTemplate") JdbcTemplate`, `PasswordEncoder` (from Task 7 — but seed needs it; define a local `BCryptPasswordEncoder` in the initializer to avoid ordering coupling — Task 7's bean will be the shared one used at login).
- Produces: `AdminAccount` (id, username, passwordHash, lastLoginAt). `AdminAccountRepository`: `findByUsername(username):Optional<AdminAccount>`, `insert(username, hash)`, `updatePassword(username, hash)`, `updateLastLogin(username)`, `count():int`. `AdminAccountInitializer implements ApplicationRunner` — empty → seed `admin`/bcrypt("admin"); `ADMIN_RESET=true` → reset to admin; `ADMIN_PASSWORD_RESET=x` → reset to x.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uz.greenwhite.gateway.config.SettingsDbConfig;
import uz.greenwhite.gateway.config.SettingsProperties;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdminAccountRepositoryTest {

    private AdminAccountRepository repo;

    @BeforeEach
    void setup() {
        SettingsProperties props = new SettingsProperties();
        props.setDbPath("mem:admin_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        SettingsDbConfig config = new SettingsDbConfig();
        DriverManagerDataSource ds = (DriverManagerDataSource) config.settingsDataSource(props);
        config.initSchema(ds);
        JdbcTemplate jdbc = config.settingsJdbcTemplate(ds);
        repo = new AdminAccountRepository(jdbc);
    }

    @Test
    void insertFindUpdate() {
        assertEquals(0, repo.count());
        repo.insert("admin", "hash1");
        assertEquals(1, repo.count());
        Optional<AdminAccount> a = repo.findByUsername("admin");
        assertTrue(a.isPresent());
        assertEquals("hash1", a.get().getPasswordHash());

        repo.updatePassword("admin", "hash2");
        assertEquals("hash2", repo.findByUsername("admin").get().getPasswordHash());

        repo.updateLastLogin("admin");
        assertNotNull(repo.findByUsername("admin").get().getLastLoginAt());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=AdminAccountRepositoryTest`
Expected: FAIL — klasslar mavjud emas.

- [ ] **Step 3: Create AdminAccount + AdminAccountRepository**

`AdminAccount.java`:

```java
package uz.greenwhite.gateway.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class AdminAccount {
    private Long id;
    private String username;
    private String passwordHash;
    private LocalDateTime lastLoginAt;
}
```

`AdminAccountRepository.java`:

```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AdminAccountRepository {

    private final @Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc;

    private static final RowMapper<AdminAccount> MAPPER = (rs, n) -> AdminAccount.builder()
            .id(rs.getLong("id"))
            .username(rs.getString("username"))
            .passwordHash(rs.getString("password_hash"))
            .lastLoginAt(rs.getObject("last_login_at", Timestamp.class) != null
                    ? rs.getTimestamp("last_login_at").toLocalDateTime() : null)
            .build();

    public int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_account", Integer.class);
    }

    public Optional<AdminAccount> findByUsername(String username) {
        return jdbc.query("SELECT * FROM admin_account WHERE username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public void insert(String username, String passwordHash) {
        jdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)",
                username, passwordHash);
    }

    public void updatePassword(String username, String passwordHash) {
        jdbc.update("UPDATE admin_account SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE username = ?",
                passwordHash, username);
    }

    public void updateLastLogin(String username) {
        jdbc.update("UPDATE admin_account SET last_login_at = CURRENT_TIMESTAMP WHERE username = ?", username);
    }
}
```

- [ ] **Step 4: Create AdminAccountInitializer**

```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the single admin account on first boot (admin/admin) and supports
 * password reset via env: ADMIN_RESET=true → reset to admin, or
 * ADMIN_PASSWORD_RESET=<value> → reset to that value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASS = "admin";

    private final AdminAccountRepository repository;
    private final Environment environment;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() == 0) {
            repository.insert(DEFAULT_USER, encoder.encode(DEFAULT_PASS));
            log.warn("Default admin account created: {}/{} — CHANGE THIS PASSWORD", DEFAULT_USER, DEFAULT_PASS);
            return;
        }
        String explicitReset = environment.getProperty("ADMIN_PASSWORD_RESET");
        if (explicitReset != null && !explicitReset.isBlank()) {
            repository.updatePassword(DEFAULT_USER, encoder.encode(explicitReset));
            log.warn("Admin password reset via ADMIN_PASSWORD_RESET env — remove the env var now");
            return;
        }
        if (Boolean.parseBoolean(environment.getProperty("ADMIN_RESET", "false"))) {
            repository.updatePassword(DEFAULT_USER, encoder.encode(DEFAULT_PASS));
            log.warn("Admin password reset to default via ADMIN_RESET=true — change it and remove the env var");
        }
    }
}
```

- [ ] **Step 5: Run test + full suite + commit**

Note: `BCryptPasswordEncoder` requires the security dependency. It gets added in Task 7. **Move the pom security dependency addition to the top of this task's Step 5** if the build fails to resolve `BCryptPasswordEncoder` — add now:

```xml
        <!-- Spring Security — admin session auth for /qosid/admin/api -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
```

**IMPORTANT:** adding spring-security locks ALL endpoints by default. Existing unit tests (Mockito, no Spring context) are unaffected, but any `@SpringBootTest` would be. There is no full-context test yet, so the suite stays green — but the SecurityFilterChain isolation in Task 7 must land before running the app. Verify: `... mvn.cmd test` → 42 tests, 0 failures.

```bash
git add pom.xml src/main/java/uz/greenwhite/gateway/admin/AdminAccount.java src/main/java/uz/greenwhite/gateway/admin/AdminAccountRepository.java src/main/java/uz/greenwhite/gateway/admin/AdminAccountInitializer.java src/test/java/uz/greenwhite/gateway/admin/AdminAccountRepositoryTest.java
git commit -m "feat(admin): admin account repository + first-boot seed + env reset; add spring-security dep"
```

---

### Task 7: Spring Security — ikki SecurityFilterChain (izolyatsiya)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/config/AdminSecurityConfig.java`
- Test: `src/test/java/uz/greenwhite/gateway/config/SecurityIsolationTest.java`

**Interfaces:**
- Produces: bean `PasswordEncoder` (BCrypt), two `SecurityFilterChain` beans (`adminSecurityChain` order 1, `apiSecurityChain` order 2). Admin chain: `/qosid/admin/api/**` needs auth (login/health permitAll), CSRF cookie-based. Api chain: everything else `permitAll`.

- [ ] **Step 1: Write the failing test (the CRITICAL isolation test)**

```java
package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards SAFETY: adding Spring Security must NOT lock the pipeline or actuator.
 * /qosid/api/** and actuator stay open; /qosid/admin/api/** is protected.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:sec_isolation_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class SecurityIsolationTest {

    @Autowired MockMvc mvc;

    @Test
    void adminApiRequiresAuth() throws Exception {
        mvc.perform(get("/qosid/admin/api/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminHealthIsOpen() throws Exception {
        mvc.perform(get("/qosid/admin/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    void pipelineStatusEndpointNotLockedBySecurity() throws Exception {
        // Unknown id → 404 from the handler, NOT 401/403 from security.
        mvc.perform(get("/qosid/api/v1/requests/0:123/status"))
                .andExpect(status().isNotFound());
    }
}
```

Note: this is the first `@SpringBootTest` — it boots the full context (Kafka/Redis beans included). If the CI/local env lacks Kafka/Redis, the context may fail on consumer startup. Mitigate: the test sets `gateway.polling.enabled=false`; Kafka consumer auto-startup can be disabled for the test with `spring.kafka.listener.auto-startup=false` — add that to the `@SpringBootTest properties`. Redis beans are lazy-created; the health endpoints and MVC layer don't touch Redis. If context still fails on Kafka admin, add `spring.kafka.bootstrap-servers=localhost:19092` and ensure the local RedPanda from the run-skill is up, OR annotate the test to run only when infra is present. Prefer `spring.kafka.listener.auto-startup=false` first.

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=SecurityIsolationTest`
Expected: FAIL — with security dep present but no config, `/qosid/api/**` returns 401 (all locked) instead of 404, and `/qosid/admin/api/health` returns 401.

- [ ] **Step 3: Implement AdminSecurityConfig**

```java
package uz.greenwhite.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.http.HttpStatus;

/**
 * Two isolated filter chains:
 *   1. adminSecurityChain — /qosid/admin/api/** requires a session (login/health open), CSRF on.
 *   2. apiSecurityChain   — everything else permitAll (pipeline + actuator keep their own auth).
 */
@Configuration
public class AdminSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/qosid/admin/api/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/qosid/admin/api/login", "/qosid/admin/api/health").permitAll()
                .anyRequest().authenticated())
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(eh -> eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .formLogin(fl -> fl.disable())
            .httpBasic(hb -> hb.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... mvn.cmd test -q -Dtest=SecurityIsolationTest`
Expected: PASS (3/3). If context fails to boot on Kafka, apply the `spring.kafka.listener.auto-startup=false` mitigation from Step 1.

- [ ] **Step 5: Run full suite + commit**

Run: `... mvn.cmd test` → 45 tests, 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/config/AdminSecurityConfig.java src/test/java/uz/greenwhite/gateway/config/SecurityIsolationTest.java
git commit -m "feat(admin): two isolated SecurityFilterChains — admin protected, pipeline+actuator open"
```

---

### Task 8: AdminAuthService + AuthController (login/logout/change-password/me + rate-limit)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/admin/LoginRateLimiter.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/AdminAuthService.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/AdminAuthController.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/dto/LoginRequest.java`, `dto/ChangePasswordRequest.java`
- Test: `src/test/java/uz/greenwhite/gateway/admin/AdminAuthServiceTest.java`

**Interfaces:**
- Consumes: `AdminAccountRepository`, `PasswordEncoder`, `LoginRateLimiter`.
- Produces: `AdminAuthService`: `authenticate(username, password):boolean` (bcrypt check + rate-limit + updateLastLogin on success), `changePassword(username, oldPw, newPw):void` (throws on wrong old). `LoginRateLimiter`: `checkAllowed(key):void` (throws `TooManyAttemptsException` after N), `recordFailure(key)`, `reset(key)`. Controller endpoints establish/clear the HttpSession.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    @Mock AdminAccountRepository repo;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminAuthService service() {
        return new AdminAuthService(repo, encoder, new LoginRateLimiter());
    }

    @Test
    void authenticateSucceedsWithCorrectPassword() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        assertTrue(s.authenticate("admin", "admin"));
        verify(repo).updateLastLogin("admin");
    }

    @Test
    void authenticateFailsWithWrongPassword() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        assertFalse(s.authenticate("admin", "wrong"));
    }

    @Test
    void rateLimitBlocksAfterFiveFailures() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        for (int i = 0; i < 5; i++) s.authenticate("admin", "wrong");
        assertThrows(LoginRateLimiter.TooManyAttemptsException.class, () -> s.authenticate("admin", "admin"));
    }

    @Test
    void changePasswordRequiresCorrectOld() {
        when(repo.findByUsername("admin")).thenReturn(Optional.of(
                AdminAccount.builder().username("admin").passwordHash(encoder.encode("admin")).build()));
        AdminAuthService s = service();
        assertThrows(IllegalArgumentException.class, () -> s.changePassword("admin", "wrong", "newpass"));
        s.changePassword("admin", "admin", "newpass");
        verify(repo).updatePassword(eq("admin"), anyString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=AdminAuthServiceTest`
Expected: FAIL — klasslar mavjud emas.

- [ ] **Step 3: Implement LoginRateLimiter**

```java
package uz.greenwhite.gateway.admin;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory login rate limiter (one instance). 5 failures / 60s window per key. */
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 60_000;

    private record Attempt(int count, long windowStart) {}

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void checkAllowed(String key) {
        Attempt a = attempts.get(key);
        if (a == null) return;
        long now = Instant.now().toEpochMilli();
        if (now - a.windowStart() < WINDOW_MS && a.count() >= MAX_FAILURES) {
            throw new TooManyAttemptsException("Too many login attempts, try again later");
        }
    }

    public void recordFailure(String key) {
        long now = Instant.now().toEpochMilli();
        attempts.compute(key, (k, a) -> {
            if (a == null || now - a.windowStart() >= WINDOW_MS) return new Attempt(1, now);
            return new Attempt(a.count() + 1, a.windowStart());
        });
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException(String m) { super(m); }
    }
}
```

- [ ] **Step 4: Implement AdminAuthService**

```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;

    public boolean authenticate(String username, String password) {
        rateLimiter.checkAllowed(username);
        var account = repository.findByUsername(username);
        if (account.isPresent() && passwordEncoder.matches(password, account.get().getPasswordHash())) {
            rateLimiter.reset(username);
            repository.updateLastLogin(username);
            return true;
        }
        rateLimiter.recordFailure(username);
        return false;
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        var account = repository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Admin account not found"));
        if (!passwordEncoder.matches(oldPassword, account.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 4) {
            throw new IllegalArgumentException("New password too short (min 4 chars)");
        }
        repository.updatePassword(username, passwordEncoder.encode(newPassword));
        log.info("Admin password changed for {}", username);
    }
}
```

- [ ] **Step 5: Implement DTOs + AuthController**

`dto/LoginRequest.java`:

```java
package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
}
```

`dto/ChangePasswordRequest.java`:

```java
package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class ChangePasswordRequest {
    private String oldPassword;
    private String newPassword;
}
```

`AdminAuthController.java`:

```java
package uz.greenwhite.gateway.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.ChangePasswordRequest;
import uz.greenwhite.gateway.admin.dto.LoginRequest;
import uz.greenwhite.gateway.exception.AuthenticationException;

import java.util.Map;

@RestController
@RequestMapping("/qosid/admin/api")
@RequiredArgsConstructor
public class AdminAuthController {

    private static final String PRINCIPAL = "admin";
    private final AdminAuthService authService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        if (!authService.authenticate(req.getUsername(), req.getPassword())) {
            throw new AuthenticationException("Invalid username or password");
        }
        var auth = new UsernamePasswordAuthenticationToken(
                req.getUsername(), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return ResponseEntity.ok(Map.of("username", req.getUsername(), "status", "AUTHENTICATED"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(Map.of("username", auth != null ? auth.getName() : "anonymous"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        authService.changePassword(auth.getName(), req.getOldPassword(), req.getNewPassword());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 6: Run test + full suite + commit**

Run: `... mvn.cmd test` → 49 tests, 0 failures.

```bash
git add src/main/java/uz/greenwhite/gateway/admin/LoginRateLimiter.java src/main/java/uz/greenwhite/gateway/admin/AdminAuthService.java src/main/java/uz/greenwhite/gateway/admin/AdminAuthController.java src/main/java/uz/greenwhite/gateway/admin/dto/LoginRequest.java src/main/java/uz/greenwhite/gateway/admin/dto/ChangePasswordRequest.java src/test/java/uz/greenwhite/gateway/admin/AdminAuthServiceTest.java
git commit -m "feat(admin): auth service + controller (login/logout/change-password/me) with rate limit"
```

---

### Task 9: SettingsController (GET/PUT settings, audit) + AuthenticationException mapping

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/admin/SettingsController.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/dto/SettingUpdateRequest.java`
- Create: `src/main/java/uz/greenwhite/gateway/admin/HealthController.java`
- Modify: `src/main/java/uz/greenwhite/gateway/exception/GlobalExceptionHandler.java` (add `LoginRateLimiter.TooManyAttemptsException` → 429)
- Test: `src/test/java/uz/greenwhite/gateway/admin/AdminApiIntegrationTest.java`

**Interfaces:**
- Consumes: `SettingsService` (Task 4), `SettingsRepository` (audit read).
- Produces: `GET /qosid/admin/api/settings` (masked list), `PUT /qosid/admin/api/settings/{key}` (body: value + secret flag), `GET /qosid/admin/api/settings/audit`, `GET /qosid/admin/api/health`.

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
        "gateway.settings.db-path=mem:admin_it_test;DB_CLOSE_DELAY=-1",
        "gateway.telegram.enabled=false"
})
@AutoConfigureMockMvc
class AdminApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/qosid/admin/api/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin"))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void unauthenticatedSettingsReturns401() throws Exception {
        mvc.perform(get("/qosid/admin/api/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenReadAndWriteSettings() throws Exception {
        MockHttpSession session = login();

        mvc.perform(get("/qosid/admin/api/settings").session(session))
                .andExpect(status().isOk());

        mvc.perform(put("/qosid/admin/api/settings/gateway.retry.max-attempts")
                        .session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "5", "secret", false))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/qosid/admin/api/settings/audit").session(session))
                .andExpect(status().isOk());
    }
}
```

Note: requires `spring-security-test` (test scope). Add to pom in Step 2.

- [ ] **Step 2: Add spring-security-test dependency**

`pom.xml` (test dependencies):

```xml
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Run test to verify it fails**

Run: `... mvn.cmd test -q -Dtest=AdminApiIntegrationTest`
Expected: FAIL — `SettingsController`/`HealthController` mavjud emas (404 on GET settings after login, or context fails).

- [ ] **Step 4: Implement DTO + controllers**

`dto/SettingUpdateRequest.java`:

```java
package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class SettingUpdateRequest {
    private String value;
    private boolean secret;
}
```

`HealthController.java`:

```java
package uz.greenwhite.gateway.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/qosid/admin/api")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
```

`SettingsController.java`:

```java
package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.SettingUpdateRequest;
import uz.greenwhite.gateway.settings.SettingsRecord;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/qosid/admin/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final @Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<List<SettingsRecord>> getAll() {
        return ResponseEntity.ok(settingsService.getAllMasked());
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> update(@PathVariable String key, @RequestBody SettingUpdateRequest req) {
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        settingsService.set(key, req.getValue(), req.isSecret(), admin);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT skey, old_value, new_value, changed_by, changed_at " +
                "FROM settings_audit ORDER BY changed_at DESC FETCH FIRST 200 ROWS ONLY"));
    }
}
```

- [ ] **Step 5: Map TooManyAttemptsException in GlobalExceptionHandler**

Add to `GlobalExceptionHandler.java` (before `handleGeneric`):

```java
    @ExceptionHandler(uz.greenwhite.gateway.admin.LoginRateLimiter.TooManyAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(
            uz.greenwhite.gateway.admin.LoginRateLimiter.TooManyAttemptsException e) {
        log.warn("Login rate limit hit: {}", e.getMessage());
        return build(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }
```

- [ ] **Step 6: Run test to verify it passes**

Run: `... mvn.cmd test -q -Dtest=AdminApiIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Run full suite + commit**

Run: `... mvn.cmd test` → 52 tests, 0 failures.

```bash
git add pom.xml src/main/java/uz/greenwhite/gateway/admin/SettingsController.java src/main/java/uz/greenwhite/gateway/admin/HealthController.java src/main/java/uz/greenwhite/gateway/admin/dto/SettingUpdateRequest.java src/main/java/uz/greenwhite/gateway/exception/GlobalExceptionHandler.java src/test/java/uz/greenwhite/gateway/admin/AdminApiIntegrationTest.java
git commit -m "feat(admin): settings CRUD API + audit + health + rate-limit 429 mapping"
```

---

### Task 10: Docs sync + version bump + lokal smoke

**Files:**
- Modify: `ai-context/CONFIGURATION.md`, `ai-context/ARCHITECTURE.md`, `ai-context/SAFETY-RULES.md`, `CLAUDE.md`
- Modify: `VERSION.md`
- Modify: `src/main/resources/application.yml` (`gateway.settings` block)

**Interfaces:** none (docs + config).

- [ ] **Step 1: Add gateway.settings yaml block**

In `application.yml` under `gateway:`:

```yaml
  # --- Settings control-plane (B1) ---
  settings:
    db-path: ${SETTINGS_DB_PATH:./data/settings}
    encryption-key: ${SETTINGS_ENCRYPTION_KEY:}
    session-ttl-minutes: ${ADMIN_SESSION_TTL_MIN:480}
```

- [ ] **Step 2: CONFIGURATION.md — new section**

Add a `## gateway.settings.* — Control-Plane (B1)` section documenting `db-path` (`SETTINGS_DB_PATH`, default `./data/settings`), `encryption-key` (`SETTINGS_ENCRYPTION_KEY`, blank=secrets disabled), `session-ttl-minutes` (`ADMIN_SESSION_TTL_MIN`, 480). Add env vars `ADMIN_RESET`, `ADMIN_PASSWORD_RESET`, and the `gateway.tenant.*` keys (`TENANT_FIELD`, `EXTRA_TENANT_FIELD`, `DEFAULT_REST_TENANT`). Add `SETTINGS_ENCRYPTION_KEY` to the production mandatory-env table.

- [ ] **Step 3: ARCHITECTURE.md — control-plane layer**

Add a subsection under Component Map: `settings/` (H2-backed SettingsService, cache + yaml→DB migration, AES secrets) and `admin/` (session auth, settings CRUD API at `/qosid/admin/api`, two isolated SecurityFilterChains). One line noting the pipeline is untouched.

- [ ] **Step 4: SAFETY-RULES.md — rule 29**

```markdown
### 29. Never merge the admin SecurityFilterChain with the pipeline chain

`AdminSecurityConfig` defines TWO chains: `adminSecurityChain` (order 1, `/qosid/admin/api/**`, session-required, CSRF on) and `apiSecurityChain` (order 2, everything else, permitAll). Collapsing them into one, or removing the `securityMatcher`, locks `/qosid/api/**` and actuator behind admin auth — the pipeline stops accepting requests and health checks fail. `SecurityIsolationTest` guards this; keep it green.
```

- [ ] **Step 5: CLAUDE.md — REST API table**

Add a row for `/qosid/admin/api/**` (admin control-plane, session auth) to the REST API Endpoints table. Note B1 in the architecture summary if appropriate.

- [ ] **Step 6: Ghost grep + version bump**

Run: `grep -rn "SETTINGS_ENCRYPTION_KEY\|gateway.settings" src/ ai-context/ application.yml` → confirm consistent naming.
`VERSION.md` → `1.4.0` (single line).

- [ ] **Step 7: Full build + test + lokal smoke**

Run:
```
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests -q
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd" test
```
Expected: BUILD SUCCESS, 52 tests, 0 failures.

Lokal smoke (RedPanda+Redis up per run-skill): run the jar, then:
- `curl -s -X POST localhost:8090/qosid/admin/api/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}'` → 200 + session cookie.
- Re-login to capture cookie + CSRF, `GET /qosid/admin/api/settings` with the session → 200 list.
- `GET /qosid/api/v1/requests/0:1/status` → 404 (pipeline still open, NOT 401).
- `GET /qosid/admin/api/settings` without session → 401.

- [ ] **Step 8: Commit**

```bash
git add ai-context/ VERSION.md CLAUDE.md src/main/resources/application.yml
git commit -m "docs: B1 settings control-plane config + SAFETY rule 29; bump version to 1.4.0"
```

---

## Yakuniy eslatmalar (executor uchun)

- Har taskdan keyin to'liq suite yashil bo'lishi shart.
- `ApiRequestConsumer`/`OracleRequestConsumer` va butun pipeline yadrosi B1'da TEGILMAYDI.
- Eng muhim test — `SecurityIsolationTest` (Task 7): pipeline+actuator ochiq qolishi. Agar `@SpringBootTest` konteksti Kafka/Redis sabab ko'tarilmasa, `spring.kafka.listener.auto-startup=false` + polling off bilan hal qilinadi; kerak bo'lsa lokal RedPanda+Redis ko'tariladi.
- Release (staging/master) — plan tugagach ALOHIDA manual qadam.
