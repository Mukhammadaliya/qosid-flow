# D-loyiha: Stop-to-edit sozlamalar + Maven reactor + avto-ulanish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sozlamalar faqat qosid servisi TO'XTATILGANDA (ops sidecar orqali) tahrirlanadi; kod umumiy `settings-core` Maven modulida yagona manbaga ega bo'ladi; start'dan keyin SPA avtomatik qayta ulanadi (token handoff).

**Architecture:** Repo bitta-pom'dan 3-modulli reactor'ga o'tadi (`settings-core` + `app` + `ops`, root aggregator `qosid-parent`). Ops sidecar settings-core orqali shared H2'ga to'g'ridan-to'g'ri yozadi (servis UP bo'lsa 409); qosid servisining barcha sozlama-PUT'lari doim 409. Avto-ulanish: ops bir-martalik token beradi (H2'da SHA-256 hash, 60s TTL), qosid `login-token` endpoint'i uni sessiyaga almashtiradi.

**Tech Stack:** Java 21, Spring Boot 3.3.0, Maven reactor, H2 (AUTO_SERVER, gateway-side HikariCP pinning), React 18 + Vite (frontend/, Maven'dan tashqarida), Vitest, Playwright.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-17-d-stop-to-edit-settings-design.md`
- E1-E5 pipeline'ga TEGILMAYDI (`ai-context/SAFETY-RULES.md` — kafka/consumer, http, oracle, state paketlari)
- Yangi umumiy kod paketi: `uz.greenwhite.qosid.settings` (settings-core). App'dagi `uz.greenwhite.gateway.*` paketlari BU LOYIHADA rename QILINMAYDI
- App JAR nomi o'zgarmaydi: `qosid-flow-1.0.0-SNAPSHOT.jar` (endi `app/target/` ichida)
- AES-GCM shifr formati o'zgarmaydi — mavjud shifrlangan qiymatlar o'qilishi SHART (contract-test bilan)
- Barcha yangi admin/ops endpointlar never-500 uslubida (aniq 4xx + JSON body)
- Maven: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; & 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd'` (lokal Windows); frontend skip: `-P!frontend`
- Commit har task oxirida, xabar `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` bilan

---

### Task 1: Maven reactor skeleti (app ko'chirish + aggregator + settings-core bo'sh modul)

**Files:**
- Create: `pom.xml` (YANGI aggregator — eski root pom `app/pom.xml`ga ko'chadi)
- Create: `settings-core/pom.xml`
- Move (git mv): `src/` → `app/src/`, `pom.xml` → `app/pom.xml`
- Modify: `app/pom.xml` (frontend profil `workingDirectory` → `../frontend`), `ops/pom.xml` (o'zgarmaydi — reactor a'zoligi root'dan), `.github/workflows/build.yml` (JAR yo'llari), `ops-local-start.cmd` (JAR yo'li), `CLAUDE.md` Quick Commands (yo'llar)
- Test: mavjud suite'lar (yangi test yo'q — bu mexanik restructure)

**Interfaces:**
- Consumes: —
- Produces: reactor build — root'da `mvn package` → `settings-core/target/qosid-settings-core-1.0.0-SNAPSHOT.jar`, `app/target/qosid-flow-1.0.0-SNAPSHOT.jar`, `ops/target/qosid-ops.jar`

- [ ] **Step 1: git mv bilan app'ni ko'chirish**

```bash
mkdir app
git mv src app/src
git mv pom.xml app/pom.xml
```

- [ ] **Step 2: Root aggregator pom yozish**

`pom.xml` (yangi fayl, to'liq mazmun):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>uz.greenwhite</groupId>
    <artifactId>qosid-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>qosid-parent</name>
    <modules>
        <module>settings-core</module>
        <module>app</module>
        <module>ops</module>
    </modules>
</project>
```

- [ ] **Step 3: settings-core skelet pom yozish**

`settings-core/pom.xml` (to'liq mazmun; spring-boot-starter-parent depMgmt uchun, modul Spring-context'siz):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.0</version>
        <relativePath/>
    </parent>
    <groupId>uz.greenwhite</groupId>
    <artifactId>qosid-settings-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>qosid-settings-core</name>
    <properties><java.version>21</java.version></properties>
    <dependencies>
        <dependency><groupId>org.springframework</groupId><artifactId>spring-jdbc</artifactId></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
        <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: app/pom.xml va ops/pom.xml'ga settings-core dependency + yo'l tuzatishlari**

`app/pom.xml` dependencies blokiga:
```xml
<dependency>
    <groupId>uz.greenwhite</groupId>
    <artifactId>qosid-settings-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```
`ops/pom.xml`ga ham xuddi shu blok. `app/pom.xml`dagi frontend profilda `<workingDirectory>` qiymatini `../frontend`ga o'zgartir (grep `workingDirectory`). H2 dependency app'da qoladi (runtime driver).

- [ ] **Step 5: Skript/CI yo'llarini yangilash**

`ops-local-start.cmd`: `target\qosid-flow-1.0.0-SNAPSHOT.jar` → `app\target\qosid-flow-1.0.0-SNAPSHOT.jar`.
`.github/workflows/build.yml`: JAR artefakt yo'li `target/` → `app/target/`; release qadamiga `ops/target/qosid-ops.jar`ni ham biriktir (mavjud upload qadamini nusxalab moslashtir).
`CLAUDE.md` Quick Commands: `mvn clean package -DskipTests` izohiga "(root'da — reactor: core+app+ops)" qo'shish; `mvn spring-boot:run` → `mvn spring-boot:run -pl app`.

- [ ] **Step 6: Build + to'liq regressiya**

Run (repo root): `mvn -q package '-P!frontend'` (JAVA_HOME bilan)
Expected: BUILD SUCCESS; `app/target/qosid-flow-1.0.0-SNAPSHOT.jar`, `ops/target/qosid-ops.jar`, `settings-core/target/qosid-settings-core-1.0.0-SNAPSHOT.jar` mavjud; app 168 + ops 23 test yashil (surefire hisobotlaridan tasdiqla). Eslatma: app testlari RocksDB lock sababli lokal servis O'CHIQ holatda ishlatiladi.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "build: Maven reactor — settings-core/app/ops modullari, aggregator qosid-parent"
```

---

### Task 2: settings-core'ga klasslarni ko'chirish (registry, cipher, repository, admin-account, rate-limiter, sxema)

**Files:**
- Move (git mv + paket rename `uz.greenwhite.gateway.settings`→`uz.greenwhite.qosid.settings`, `uz.greenwhite.gateway.admin`→`uz.greenwhite.qosid.settings`):
  - `app/src/main/java/uz/greenwhite/gateway/settings/{SettingsRegistry,SettingsCipher,SettingsRecord,SettingsRepository}.java` → `settings-core/src/main/java/uz/greenwhite/qosid/settings/`
  - `app/src/main/java/uz/greenwhite/gateway/admin/{AdminAccount,AdminAccountRepository,LoginRateLimiter}.java` → `settings-core/src/main/java/uz/greenwhite/qosid/settings/`
  - `app/src/main/resources/settings-schema.sql` → `settings-core/src/main/resources/settings-schema.sql`
  - Testlar: `SettingsCipherTest`, `SettingsRegistryTest`, `SettingsRepositoryTest`, `AdminAccountRepositoryTest`, `SettingsDataSourcePinningTest`dan repository qismi EMAS (u app'da qoladi — DataSource app'niki) → `settings-core/src/test/java/uz/greenwhite/qosid/settings/`
- Modify: app'dagi BARCHA import'lar (`grep -rl "gateway.settings.Settings\|gateway.admin.AdminAccount\|gateway.admin.LoginRateLimiter" app/src | xargs sed` yoki qo'lda); `SettingsCipher` konstruktori `SettingsProperties`dan uzratiladi (pastga qarang); `ops/src/main/java/uz/greenwhite/ops/AdminAuthDao.java` core `AdminAccountRepository`dan foydalanadi
- Create: `settings-core/src/test/java/uz/greenwhite/qosid/settings/SettingsCipherContractTest.java`
- Test: ko'chgan testlar + yangi contract-test

**Interfaces:**
- Consumes: Task 1 reactor
- Produces (keyingi tasklar tayanadi):
  - `SettingsCipher(String rawKey)` — `String encrypt(String plaintext)`, `String decrypt(String ciphertext)`, `boolean isEnabled()` (kalit bo'sh → false). Spring annotatsiyasiz POJO; app'da `@Bean` sifatida `SettingsProperties.getEncryptionKey()` bilan quriladi.
  - `SettingsRepository(JdbcTemplate jdbc)` — `Optional<SettingsRecord> find(String key)`, `List<SettingsRecord> findAll()`, `void upsert(String key, String value, boolean secret, String author)`, `void insertAudit(String key, String oldVal, String newVal, String author)`, `List<Map<String,Object>> findAudit(int limit)`
  - `SettingsRegistry` — `static boolean isEditable(String key)`, mavjud metadata metodlari o'zgarishsiz
  - `AdminAccountRepository(JdbcTemplate jdbc)` — mavjud imzolar o'zgarishsiz
  - `LoginRateLimiter` — mavjud imzolar o'zgarishsiz
  - `settings-schema.sql` — classpath'da `settings-schema.sql` nomi O'ZGARMAYDI (app `SettingsDbConfig.initSchema` va ops shu nomdan o'qiydi)

- [ ] **Step 1: git mv + paket deklaratsiyalarini yangilash** (yuqoridagi ro'yxat bo'yicha; har faylda `package uz.greenwhite.qosid.settings;`)

- [ ] **Step 2: SettingsCipher'ni SettingsProperties'dan uzratish**

Hozir: `SettingsCipher` `@Component` va `SettingsProperties`ga bog'liq. Core versiyasi POJO:

```java
public class SettingsCipher {
    private final byte[] key; // null => disabled
    public SettingsCipher(String rawKey) { this.key = deriveKey(rawKey); } // mavjud derive logikasi saqlanadi
    public boolean isEnabled() { return key != null; }
    // encrypt/decrypt tanasi AYNAN hozirgidek (AES-GCM, format o'zgarmaydi)
}
```

App'da `SettingsDbConfig` (yoki mavjud config klass)ga bean qo'shiladi:
```java
@Bean
public SettingsCipher settingsCipher(SettingsProperties props) {
    return new SettingsCipher(props.getEncryptionKey());
}
```
`SettingsService`dagi field-injection/constructor mos ravishda bean'ni oladi (import yangilanadi).

- [ ] **Step 3: Ops AdminAuthDao'ni core repository'ga o'tkazish**

`AdminAuthDao.verify` ichidagi inline SQL o'rniga:
```java
private final AdminAccountRepository accounts; // new AdminAccountRepository(opsJdbc) — OpsDbConfig'da bean
// verify: accounts.find(username).map(a -> encoder.matches(password, a.getPasswordHash())).orElse(false)
// 1x-retry sikli (2026-07-17 fix) SAQLANADI — faqat ichki chaqiruv almashadi
```
`OpsAuthTest.seedAdmin`dagi CREATE TABLE o'rniga core `settings-schema.sql`ni ishlatish shart emas — mavjud test o'z jadvalini yaratadi, ishlayveradi.

- [ ] **Step 4: Cipher contract-test yozish (failing bo'lishi shart emas — ko'chirilgan kod, lekin fixture YANGI)**

`SettingsCipherContractTest.java`:
```java
class SettingsCipherContractTest {
    private static final String KEY = "contract-test-key-2026";

    @Test
    void roundTripAcrossInstances() {
        String ct = new SettingsCipher(KEY).encrypt("maxfiy-qiymat-™");
        assertEquals("maxfiy-qiymat-™", new SettingsCipher(KEY).decrypt(ct));
    }

    /** Format-freeze: shu fixture task davomida bir marta generatsiya qilinadi va literal saqlanadi.
     *  Kelajakda cipher o'zgartirilsa bu test eski qiymatlar o'qilishini kafolatlaydi. */
    @Test
    void decryptsFrozenFixture() {
        String frozen = "<TASK DAVOMIDA: new SettingsCipher(KEY).encrypt(\"frozen-plaintext\") natijasini shu yerga literal joylashtir>";
        assertEquals("frozen-plaintext", new SettingsCipher(KEY).decrypt(frozen));
    }

    @Test
    void disabledWhenKeyBlank() {
        assertFalse(new SettingsCipher("").isEnabled());
    }
}
```
(Fixture literal'ini generatsiya qilib joylashtirgach test doimiy bo'ladi.)

- [ ] **Step 5: Build + regressiya**

Run: `mvn -q package '-P!frontend'`
Expected: BUILD SUCCESS; core testlari (ko'chganlar + 3 contract) yashil; app 168−(ko'chgan ~10) + ops 23 yashil; jami kamaymagan (faqat joy almashgan).

- [ ] **Step 6: Ghost tekshiruv**

Run: `grep -rn "gateway.settings.SettingsRegistry\|gateway.settings.SettingsCipher\|gateway.settings.SettingsRepository\|gateway.admin.AdminAccountRepository\|gateway.admin.LoginRateLimiter" app/src ops/src`
Expected: bo'sh (hech qanday eski import qolmagan).

- [ ] **Step 7: Commit** — `git add -A && git commit -m "refactor(core): settings-core moduliga registry/cipher/repository/admin-account ko'chirildi"`

---

### Task 3: Ops settings API (GET/PUT/audit, UP→409 guard)

**Files:**
- Create: `ops/src/main/java/uz/greenwhite/ops/OpsSettingsController.java`, `ops/src/main/java/uz/greenwhite/ops/OpsSettingsService.java`
- Modify: `ops/src/main/resources/application.yml` (`ops.settings-encryption-key: ${SETTINGS_ENCRYPTION_KEY:}`), `OpsDbConfig` (SettingsRepository + SettingsCipher bean'lari)
- Test: `ops/src/test/java/uz/greenwhite/ops/OpsSettingsApiTest.java`

**Interfaces:**
- Consumes: Task 2 — `SettingsRepository`, `SettingsRegistry`, `SettingsCipher`; mavjud `GatewayStatusService.isUp():boolean`
- Produces (SPA tayanadi):
  - `GET /ops/api/settings` → `200 [{"key","value","secret","group","label","type","editable"}]` — secret bo'lsa `value:"***"` (qiymat bo'sh bo'lsa `""`)
  - `PUT /ops/api/settings/{key}` body `{"value":"..."}` → `200 {"key","status":"SAVED"}` | `409 {"error":"service-running"}` | `400 {"error":"not-editable"|"unknown-key"|"encryption-unavailable"}`
  - `GET /ops/api/settings/audit` → `200 [{key, old_value, new_value, author, changed_at}]` (masked)

- [ ] **Step 1: Failing testlar yozish** (`OpsSettingsApiTest`, `@SpringBootTest` + MockMvc, `@MockBean GatewayStatusService`):

```java
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opssettings;DB_CLOSE_DELAY=-1",
        "ops.settings-encryption-key=test-aes-key",
        "ops.gateway.start-command=echo s", "ops.gateway.stop-command=echo s"})
@AutoConfigureMockMvc
class OpsSettingsApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate opsJdbc;
    @MockBean GatewayStatusService status;
    // seed: settings-schema.sql core'dan yuklanadi (OpsDbConfig init) + admin_account + 2 settings qatori
    //   (editable non-secret: "gateway.api.base-url"="http://old", secret: "gateway.source.auth.basic.password")
    // login helper: OpsAuthTest'dagi kabi sessiya + OPS-XSRF-TOKEN

    @Test void listMasksSecrets()                 // GET → base-url ochiq, password "***"
    @Test void putRejectedWhenGatewayUp()         // status.isUp()=true → PUT → 409 service-running, DB o'zgarmagan
    @Test void putSavesWhenGatewayDown()          // isUp()=false → PUT base-url → 200, DB'da yangi qiymat, audit qatori bor
    @Test void putEncryptsSecret()                // isUp()=false → PUT password → DB'dagi qiymat plaintext EMAS va cipher.decrypt bilan o'qiladi
    @Test void putUnknownKey400()                 // registry'da yo'q kalit → 400 unknown-key
    @Test void putReadOnlyKey400()                // isEditable=false kalit (masalan gateway.kafka.bootstrap-servers) → 400 not-editable
    @Test void putSecretWithoutKey400()           // encryption-key bo'sh kontekst (@Nested yoki alohida property) → 400 encryption-unavailable
    @Test void allEndpointsRequireSession()       // sessiyasiz GET/PUT → 401
}
```

- [ ] **Step 2: Testlarni ishga tushirib yiqilishini ko'rish** — `mvn -q test -pl ops -Dtest=OpsSettingsApiTest`; Expected: 404/bean topilmadi xatolari.

- [ ] **Step 3: Implementatsiya**

`OpsSettingsService` (asosiy logika, never-500):
```java
@Service @RequiredArgsConstructor
public class OpsSettingsService {
    private final SettingsRepository repo; private final SettingsCipher cipher;
    private final GatewayStatusService status;

    public List<Map<String,Object>> list() { /* repo.findAll() + SettingsRegistry metadata merge, secret→"***" */ }

    public ResponseEntity<Map<String,String>> put(String key, String value) {
        if (status.isUp()) return ResponseEntity.status(409).body(Map.of("error","service-running"));
        Optional<SettingsRecord> existing = repo.find(key);
        if (existing.isEmpty() && !SettingsRegistry.isKnown(key)) return bad("unknown-key");
        if (!SettingsRegistry.isEditable(key)) return bad("not-editable");
        boolean secret = existing.map(SettingsRecord::isSecret).orElse(false);
        String stored = value;
        if (secret) {
            if (!cipher.isEnabled()) return bad("encryption-unavailable");
            stored = cipher.encrypt(value);
        }
        repo.insertAudit(key, existing.map(r -> secret ? "***" : r.getValue()).orElse(null), secret ? "***" : value, "admin@ops");
        repo.upsert(key, stored, secret, "admin@ops");
        return ResponseEntity.ok(Map.of("key", key, "status", "SAVED"));
    }
}
```
Eslatma: `SettingsRegistry.isKnown(key)` yo'q bo'lsa core'ga qo'shiladi (`static boolean isKnown` — metadata map'da bormi). `OpsDbConfig`ga bean'lar: `new SettingsRepository(opsJdbc)`, `new SettingsCipher(encKey)`, va H2 sxema init core `settings-schema.sql`dan (`ScriptUtils.executeSqlScript`, idempotent). Controller yupqa: service'ga delegatsiya, `GET /audit` → `repo.findAudit(200)`.

- [ ] **Step 4: Testlar yashil** — `mvn -q test -pl ops`; Expected: barcha ops testlari (23 + yangi 8) PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(ops): stop-to-edit settings API — GET/PUT/audit, UP→409 guard, AES secretlar"`

---

### Task 4: Qosid servisida qat'iy 409 (barcha sozlama-yozuvlar bloklanadi) + restart-pending demontaji

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/admin/SettingsController.java` (PUT → 409), `SourceAuthController.java` (PUT → 409; `POST /test` QOLADI), `TenantMappingController.java` (sozlama-yozuv PUT → 409; `POST /test` diagnostika QOLADI), `AiController.java` (PUT config → 409; `POST /analyze`, `GET /status` QOLADI)
- Delete: `app/src/main/java/uz/greenwhite/gateway/settings/RestartPendingResetRunner.java` + restart-pending yozish logikasi (SettingsController/SettingsService'dan `gateway._meta.restart-pending` set qilish joylari)
- Test: mavjud controller testlarini yangilash (PUT kutilmalari 409ga) + `app/src/test/java/uz/greenwhite/gateway/admin/StopRequiredEnforcementTest.java`

**Interfaces:**
- Consumes: —
- Produces (SPA tayanadi): barcha yozuv endpointlari `409 {"error":"stop-required"}` (JSON shakli aynan shu); GET'lar o'zgarishsiz (jumladan `GET /settings/registry` — `restartPending` maydoni javobdan OLIB TASHLANADI)

- [ ] **Step 1: Failing test yozish** (`StopRequiredEnforcementTest` — login-helper mavjud testlardan):

```java
// har biri authenticated sessiya bilan:
@Test void settingsPutReturns409()      // PUT /qosid/admin/api/settings/gateway.api.base-url → 409 {"error":"stop-required"}
@Test void sourceAuthPutReturns409()    // PUT /qosid/admin/api/source-auth → 409
@Test void tenantMappingPutReturns409() // PUT /qosid/admin/api/tenant-mapping (yozuv endpointi) → 409
@Test void aiConfigPutReturns409()      // PUT /qosid/admin/api/ai/config → 409
@Test void tenantTestStillWorks()       // POST /qosid/admin/api/tenant-mapping/test → 200 (diagnostika bloklanmaydi)
@Test void settingsGetStillWorks()      // GET /qosid/admin/api/settings → 200
```

- [ ] **Step 2: Yiqilishini ko'rish** — PUT'lar hozir 200 qaytaradi → testlar FAIL.

- [ ] **Step 3: Implementatsiya** — har yozuv-metodi tanasining boshida:

```java
return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "stop-required"));
```
(metod tanasining qolgan yozuv qismi O'CHIRILADI emas — metodning o'zi shu javobga qisqartiriladi; ichki service-yozuv metodlari o'z joyida qoladi, chunki migration/initializer ishlatadi). Restart-pending: `RestartPendingResetRunner` o'chiriladi, registry javobidan `restartPending` maydoni olinadi, mavjud unit-testlaridagi kutilmalar yangilanadi (`SettingsServiceRestartPendingTest` o'chiriladi).

- [ ] **Step 4: To'liq app suite yashil** — `mvn -q test -pl app '-P!frontend'`; Expected: PASS (yangilangan kutilmalar bilan).

- [ ] **Step 5: Commit** — `git commit -m "feat(app): qat'iy stop-to-edit — barcha sozlama-PUT'lar 409, restart-pending demontaj"`

---

### Task 5: Token handoff backend (core repo + ops handoff + app login-token)

**Files:**
- Create: `settings-core/src/main/java/uz/greenwhite/qosid/settings/LoginHandoffRepository.java`; `settings-core`dagi `settings-schema.sql`ga jadval
- Modify: `ops/src/main/java/uz/greenwhite/ops/OpsAuthController.java` (`POST /ops/api/handoff`), `app/src/main/java/uz/greenwhite/gateway/admin/AdminAuthController.java` (`POST /qosid/admin/api/login-token`), app `SecurityConfig` admin zanjiri (`/login-token` permitAll), `AdminAuthService` (token orqali sessiya — mavjud login yo'lini refactor-reuse)
- Test: `settings-core/.../LoginHandoffRepositoryTest.java`, `ops/.../OpsHandoffTest.java`, `app/.../LoginTokenTest.java`

**Interfaces:**
- Consumes: Task 2 core
- Produces:
  - sxema: `CREATE TABLE IF NOT EXISTS login_handoff (token_hash VARCHAR(64) PRIMARY KEY, username VARCHAR(100) NOT NULL, expires_at TIMESTAMP NOT NULL);`
  - `LoginHandoffRepository(JdbcTemplate)` — `void insert(String tokenHash, String username, Instant expiresAt)`, `Optional<String> consume(String tokenHash)` (topilsa VA muddati o'tmagan bo'lsa username qaytarib qatorni O'CHIRADI — bitta `DELETE ... RETURNING` yoki select+delete tranzaksiyasiz ketma-ketligi, H2'da `SELECT`+`DELETE WHERE token_hash=? AND expires_at>now` yetarli), `int purgeExpired()`
  - `POST /ops/api/handoff` (sessiya+CSRF) → `200 {"token":"<base64url 32-bayt>"}`
  - `POST /qosid/admin/api/login-token` body `{"token":"..."}` (permitAll, rate-limited) → `200 {"username","status":"AUTHENTICATED"}` + sessiya/XSRF cookie (oddiy login bilan bir xil) | `401 {"error":"invalid-token"}`
  - Hash: `SHA-256(token)` hex lowercase — ikkala tomonda bir xil util (core'da `static String sha256Hex(String)` helper `LoginHandoffRepository`da)

- [ ] **Step 1: Core repo failing testlari** (mem H2, sxema init):

```java
@Test void consumeReturnsUsernameOnceAndDeletes()  // insert → consume=admin → ikkinchi consume=empty
@Test void consumeRejectsExpired()                  // expires_at=now-1s → empty
@Test void purgeExpiredDeletesOnlyExpired()
```

- [ ] **Step 2: Yiqilish → repo implementatsiyasi → yashil** (`mvn -q test -pl settings-core`)

- [ ] **Step 3: Ops handoff failing testlari**: sessiyasiz → 401; sessiya+CSRF bilan → 200 va `login_handoff`da 1 qator (hash != token, expires ~60s). Implementatsiya: `SecureRandom` 32 bayt → base64url; `LoginHandoffRepository` bean `OpsDbConfig`da. Yashil → o'tish.

- [ ] **Step 4: App login-token failing testlari**:

```java
@Test void validTokenCreatesSession()   // to'g'ridan-to'g'ri repo.insert(hash,"admin",+60s) seed → POST → 200 + Set-Cookie sessiya + keyin GET /me 200
@Test void reusedTokenRejected()        // ikkinchi POST → 401
@Test void expiredTokenRejected()       // -1s seed → 401
@Test void rateLimited()                // LoginRateLimiter siyosati login bilan bir xil (ko'p noto'g'ri token → 429)
```
Implementatsiya: `AdminAuthController.loginToken` → hash → `repo.consume` → topilsa mavjud sessiya-yaratish yo'li (session rotation + SecurityContext) — `AdminAuthService`dagi login-success qismini `establishSession(HttpServletRequest, String username)` metodiga ajratib ikkala joydan chaqirish. SecurityConfig: `/qosid/admin/api/login-token` permitAll + CSRF exempt (login bilan bir xil).

- [ ] **Step 5: To'liq regressiya** — `mvn -q package '-P!frontend'`; Expected: BUILD SUCCESS, barcha modullar yashil.

- [ ] **Step 6: Commit** — `git commit -m "feat: login-token handoff — ops token beradi, app sessiyaga almashtiradi (60s TTL, bir-martalik)"`

---

### Task 6: Ops login rate-limit (hardening)

**Files:**
- Modify: `ops/src/main/java/uz/greenwhite/ops/OpsAuthController.java` (yoki auth service) — core `LoginRateLimiter`ni qo'llash; `OpsDbConfig`/config bean
- Test: `ops/src/test/java/uz/greenwhite/ops/OpsLoginRateLimitTest.java`

**Interfaces:**
- Consumes: Task 2 `LoginRateLimiter` (app'dagi siyosat bilan bir xil: per-username, faqat mavjud akkaunt)
- Produces: `POST /ops/api/login` — limit oshsa `429 {"error":"rate-limited"}`

- [ ] **Step 1: Failing test** — mavjud akkauntga N+1 noto'g'ri parol → oxirgisi 429; noto'g'ri username'ga N+1 → 401 qolaveradi (soxta-akkaunt enumeration himoyasi app bilan bir xil); to'g'ri parol limitdan keyin ham 429 (siyosat app'niki bilan aynan).
- [ ] **Step 2: Yiqilish → implementatsiya (app `AdminAuthController`dagi ishlatish uslubini aynan takrorlash) → yashil.**
- [ ] **Step 3: Commit** — `git commit -m "feat(ops): login rate-limit — app bilan bir xil siyosat"`

---

### Task 7: SPA — ikki rejimli Settings (General page) + nav gating

**Files:**
- Create: `frontend/src/api/opsSettings.ts`
- Modify: `frontend/src/pages/settings/GeneralSettingsPage.tsx` (rejim-aware), `frontend/src/layout/AppLayout.tsx` (Settings nav: `disabled: !gatewayUp && !opsUp`; restart-pending banner olib tashlanadi), `frontend/src/i18n/uz.json` (yangi matnlar)
- Test: `frontend/src/api/opsSettings.test.ts`, `GeneralSettingsPage.test.tsx` yangilanadi

**Interfaces:**
- Consumes: Task 3 ops API shakllari (aynan): `GET /ops/api/settings`, `PUT /ops/api/settings/{key}`, `GET /ops/api/settings/audit`; `useAuth()` → `{gatewayUp, opsUp}`
- Produces: `opsSettings.getAll(): Promise<OpsSetting[]>` (`OpsSetting = {key:string; value:string; secret:boolean; group:string; label:string; type:string; editable:boolean}`), `opsSettings.put(key: string, value: string): Promise<void>`, `opsSettings.getAudit(): Promise<AuditRow[]>` — ops axios klienti `X-OPS-XSRF-TOKEN` bilan (mavjud `api/ops.ts` uslubi)

- [ ] **Step 1: Failing vitest'lar** — `opsSettings.test.ts` (msw/mock-adapter bilan URL+header assert); `GeneralSettingsPage.test.tsx` yangilanishi:
  - `gatewayUp=true` → barcha edit tugmalar disabled + qulf + banner matni `settings.stopToEdit` ("Tahrirlash uchun servisni Ops sahifasidan to'xtating")
  - `gatewayUp=false, opsUp=true` → jadval ops ma'lumotidan, edit modal ishlaydi, saqlash `PUT /ops/api/settings/{key}`ga boradi
  - effect-class rang-badge'lar o'rniga bitta izoh matni `settings.appliesOnStart`
- [ ] **Step 2: Yiqilish → implementatsiya → vitest yashil** (`cd frontend && npx vitest run`)
- [ ] **Step 3: tsc toza** — `npx tsc --noEmit`; Expected: 0 error.
- [ ] **Step 4: Commit** — `git commit -m "feat(ui): Settings ikki rejimli — UP read-only, DOWN ops orqali tahrirlash"`

---

### Task 8: SPA — typed formalar (Source Auth / Tenant / AI) down-rejimda

**Files:**
- Create: `frontend/src/api/settingsKeys.ts` — forma-maydon ↔ kalit xaritalari (CONFIGURATION.md'dan aniq kalitlar: `gateway.source.auth.type|basic.username|basic.password|apikey.header|apikey.value|oauth2.token-url|oauth2.client-id|oauth2.client-secret|oauth2.scope|oauth2.cache-ttl-seconds`, `gateway.tenant.field-name|extra-field-name|default-rest-tenant|trust-extra-tenant-header`, `gateway.ai.base-url|api-key|model`)
- Modify: `SourceAuthPage.tsx`, `TenantMappingPage.tsx`, AI config tab komponenti — down-rejimda `settingsKeys` xaritasi orqali `opsSettings.put` bilan yozish; secret maydon semantikasi: bo'sh qoldirilsa PUT yuborilmaydi (keep), kiritilsa yuboriladi; "Test" tugmalari down'da disabled + tooltip `settings.testNeedsService`
- Test: mavjud sahifa testlari yangilanadi + xarita testi

**Interfaces:**
- Consumes: Task 7 `opsSettings`; Task 3 API
- Produces: `settingsKeys.sourceAuth(form: SourceAuthForm): Array<{key: string; value: string; secret: boolean}>` va tenant/ai uchun mos funksiyalar — bo'sh secret maydonlar ro'yxatga KIRMAYDI

- [ ] **Step 1: Failing vitest** — xarita funksiyasi (to'ldirilgan forma → kutilgan key/value ro'yxati; bo'sh parol → parol kaliti yo'q); SourceAuthPage down-rejim: saqlash N ta `PUT /ops/api/settings/...` chaqiradi, Test tugmasi disabled.
- [ ] **Step 2: Yiqilish → implementatsiya → yashil + tsc toza.**
- [ ] **Step 3: Commit** — `git commit -m "feat(ui): typed formalar down-rejimda ops generic API orqali"`

---

### Task 9: SPA — avto-ulanish (handoff hook)

**Files:**
- Modify: `frontend/src/api/ops.ts` (`requestHandoff(): Promise<{token:string}>` — `POST /ops/api/handoff`), `frontend/src/api/client.ts` yoki yangi funksiya (`loginWithToken(token): POST /qosid/admin/api/login-token`), `frontend/src/auth/AuthContext.tsx` — down→up o'tish kuzatilganda (opsUp bo'lsa): handoff → login-token → `refresh({silent:true})`; xato bo'lsa jim (mavjud xatti-harakat)
- Test: `AuthContext.test.tsx` yangi holatlar

**Interfaces:**
- Consumes: Task 5 endpointlari; `OpsPage`dagi mavjud transition-detect (prevUpRef) — hook'ni AuthContext'ga ko'chirish: `refresh` ichida `gatewayUp` false→true o'tishini aniqlash o'rniga, `OpsPage` transition'da `attemptHandoff()`ni chaqiradi (AuthContext'dan eksport)
- Produces: `useAuth().attemptHandoff(): Promise<void>` — muvaffaqiyatda gatewayUp=true + user sessiyali; yiqilishda no-op

- [ ] **Step 1: Failing vitest** — mock: ops handoff 200 {token}, login-token 200 → attemptHandoff'dan keyin `/me` qayta so'raladi va gatewayUp=true; handoff 401 → hech qanday login-token chaqirilmaydi, xato yutiladi.
- [ ] **Step 2: Yiqilish → implementatsiya → yashil + tsc toza.**
- [ ] **Step 3: Commit** — `git commit -m "feat(ui): start'dan keyin avto-ulanish — handoff token bilan sessiya"`

---

### Task 10: E2E + docs + VERSION

**Files:**
- Create: `frontend/e2e/stop-to-edit.spec.ts`
- Modify: `CLAUDE.md` (endpoint jadvali: ops settings API qatori, 409 izohlar, reactor quick-commands), `ai-context/ARCHITECTURE.md` (Ops console bo'limi + Admin control-plane: stop-to-edit modeli, handoff), `ai-context/CONFIGURATION.md` (`ops.settings-encryption-key`, deploy checklist v1.13→v1.14: ExecStart `app/target/...`, ops env'ga SETTINGS_ENCRYPTION_KEY), `ai-context/OVERVIEW.md` (repo strukturasi reactor), `VERSION.md` → `1.14.0`
- Test: Playwright

**Interfaces:**
- Consumes: hamma oldingi tasklar
- Produces: yashil e2e + aktual docs

- [ ] **Step 1: E2E ssenariy yozish** (lokal to'liq stack talab qiladi — docker infra + ikkala JVM + vite; mavjud e2e konvensiyasiga rioya):

```typescript
// stop-to-edit.spec.ts — asosiy oqim:
// 1. login (admin) → /settings read-only (qulf ko'rinadi)
// 2. /ops → To'xtatish → confirm → badge O'chiq
// 3. /settings → endi tahrirlash mumkin → gateway.api.base-url qiymatini o'zgartirish → saqlandi
// 4. /ops → Ishga tushirish → badge Ishlayapti → LOGIN SAHIFASIZ nav to'liq ochiq (avto-ulanish)
// 5. /settings → yangi qiymat read-only ko'rinishda
```

- [ ] **Step 2: E2E lokal ishga tushirish** — `cd frontend && npx playwright test stop-to-edit`; Expected: PASS (servisni test o'zi to'xtatib-yoqadi; ops env'lari sozlangan bo'lishi shart).
- [ ] **Step 3: Docs yangilash** (yuqoridagi ro'yxat; ghost-tekshiruv: `grep -rn "restart-pending\|restartPending" ai-context/ frontend/src app/src` → faqat tarixiy spec/plan'larda qolsin), VERSION → 1.14.0.
- [ ] **Step 4: To'liq yakuniy regressiya** — root `mvn -q package` (frontend profil BILAN — dist quriladi) + `npx vitest run` + surefire jamlanmalari; Expected: hammasi yashil.
- [ ] **Step 5: Commit** — `git commit -m "docs+e2e: D-loyiha stop-to-edit yakuni; VERSION 1.14.0"`

---

## Self-review yakuni

- Spec qamrovi: reactor (T1-2), ops API+guard (T3), qat'iy 409+restart-pending demontaj (T4), handoff (T5, T9), hardening (T6), SPA ikki rejim (T7-8), e2e+docs+deploy (T10) — spec bo'limlari to'liq xaritalangan.
- Turlar/imzolar: `SettingsCipher(String)`, `LoginHandoffRepository.consume→Optional<String>`, ops API JSON shakllari T3↔T7, `attemptHandoff` T9↔T5 — izchil.
- Eslatma ijrochilarga: app testlari lokal ishlab turgan servis bilan to'qnashadi (RocksDB LOCK) — test oldidan servisni to'xtating.
