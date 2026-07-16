# Ops konsoli (sidecar) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Alohida doim-ishlaydigan Spring Boot sidecar (`qosid-ops`, :8092) — gateway JVM'ni bitta toggle bilan START/STOP qiladi (UI o'chmasdan) va `gateway.log`'ni real-time SSE bilan alohida oynada ko'rsatadi.

**Architecture:** Yangi mustaqil Maven modul `ops/` (o'z JAR, o'z systemd unit). HTTP-health orqali gateway holatini biladi; `ProcessBuilder` bilan sozlanadigan start/stop buyruqni ishga tushiradi; `gateway.log`'ni tail qiladi (SSE); shared H2 `admin_account`'dan (AUTO_SERVER) BCrypt bilan login. Gateway'da yagona o'zgarish: `SettingsDbConfig` H2 URL'iga `AUTO_SERVER=TRUE`.

**Tech Stack:** Java 21, Spring Boot 3.3.0 (web + security + jdbc), H2 2.2.224, BCrypt, Spring MVC `SseEmitter`; vanilla-JS static sahifa (npm/Vite YO'Q).

## Global Constraints

- Ish `debug` branch'da (CLAUDE.md branching policy) — `master`'ga tegilmaydi.
- Yangi modul paketi: `uz.greenwhite.ops`. Modul: `ops/` (o'z `pom.xml`, `spring-boot-starter-parent` 3.3.0, `java.version` 21).
- **Pipeline (E1-E5) va gateway kodi tegilmaydi** — gateway'da YAGONA o'zgarish: `SettingsDbConfig.java` H2 URL'iga `;AUTO_SERVER=TRUE` (control-plane DB, `mem:` testlar uchun guard bilan).
- Sidecar gateway'dan **mustaqil** — gateway o'chgan bo'lsa ham ishlaydi.
- **Command-injection yo'q:** start/stop buyruqlari config'dan (`ops.gateway.start-command`/`stop-command`), foydalanuvchi kiritmaydi.
- Auth: shared H2 `admin_account` (username, password_hash BCrypt) — admin UI bilan bir xil parol. Shifrlash-kaliti kerak emas.
- `never-throw`: status/control/log xizmatlari xato'da strukturaviy natija qaytaradi, exception tashlamaydi.
- Toggle: gateway UP → stop, DOWN → start.
- Config kalitlar: `ops.port`=8092, `ops.gateway.health-url`, `ops.gateway.start-command`, `ops.gateway.stop-command`, `ops.log-file`, `ops.settings-db-path`.
- mvn PATH'da yo'q. Test: `JAVA_HOME="/c/Program Files/Java/jdk-21" "/c/Program Files/JetBrains/IntelliJ IDEA 2025.3.3/plugins/maven/lib/maven3/bin/mvn.cmd" -q test -Dtest=... -f ops/pom.xml` (ops moduli uchun `-f ops/pom.xml`); gateway testi uchun `-f pom.xml`.

---

### Task 1: ops moduli skeleti + gateway AUTO_SERVER o'zgarishi

**Files:**
- Create: `ops/pom.xml`
- Create: `ops/src/main/java/uz/greenwhite/ops/OpsApplication.java`
- Create: `ops/src/main/resources/application.yml`
- Modify: `src/main/java/uz/greenwhite/gateway/config/SettingsDbConfig.java:28`
- Test: `ops/src/test/java/uz/greenwhite/ops/OpsApplicationTest.java`

**Interfaces:**
- Produces: buildable `ops/` modul, `OpsApplication` (Spring Boot main), config kalitlar.

- [ ] **Step 1: Create the ops module pom**

`ops/pom.xml`:
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
    <artifactId>qosid-ops</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>qosid-ops</name>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
        <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><version>2.2.224</version></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create the application main + config**

`ops/src/main/java/uz/greenwhite/ops/OpsApplication.java`:
```java
package uz.greenwhite.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}
```

`ops/src/main/resources/application.yml`:
```yaml
server:
  port: ${OPS_PORT:8092}

ops:
  gateway:
    health-url: ${OPS_GATEWAY_HEALTH_URL:http://localhost:8090/qosid/admin/api/health}
    start-command: ${OPS_GATEWAY_START_COMMAND:}
    stop-command: ${OPS_GATEWAY_STOP_COMMAND:}
  log-file: ${OPS_LOG_FILE:./logs/gateway.log}
  settings-db-path: ${OPS_SETTINGS_DB_PATH:./data/settings}

logging:
  level:
    root: INFO
    uz.greenwhite.ops: INFO
```

- [ ] **Step 3: Write the failing context-loads test**

`ops/src/test/java/uz/greenwhite/ops/OpsApplicationTest.java`:
```java
package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opstest;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo start",
        "ops.gateway.stop-command=echo stop"
})
class OpsApplicationTest {
    @Test
    void contextLoads() {
        // Fails until OpsApplication + a valid context (security, datasource) exist.
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" "/c/Program Files/JetBrains/IntelliJ IDEA 2025.3.3/plugins/maven/lib/maven3/bin/mvn.cmd" -q test -Dtest=OpsApplicationTest -f ops/pom.xml`
Expected: FAIL (no main class / context missing) — until Steps 1-2 compile. After Steps 1-2 it may pass contextLoads; that is acceptable for this scaffolding task (the assertion is context startup).

- [ ] **Step 5: Add AUTO_SERVER to the gateway settings DB (with mem: guard)**

`src/main/java/uz/greenwhite/gateway/config/SettingsDbConfig.java` — replace line 28:
```java
// eski:
//     ds.setUrl("jdbc:h2:" + props.getDbPath() + ";MODE=LEGACY");
// yangi (mem: testlar AUTO_SERVER'ni qo'llab-quvvatlamaydi → guard):
        String path = props.getDbPath();
        String autoServer = path.startsWith("mem:") ? "" : ";AUTO_SERVER=TRUE";
        ds.setUrl("jdbc:h2:" + path + ";MODE=LEGACY" + autoServer);
```

- [ ] **Step 6: Run gateway settings tests (regression) + ops build**

Run gateway: `JAVA_HOME="/c/Program Files/Java/jdk-21" "/c/Program Files/JetBrains/IntelliJ IDEA 2025.3.3/plugins/maven/lib/maven3/bin/mvn.cmd" -q test -Dtest=SettingsRepositoryTest,SettingsServiceTest,SettingsMigrationRunnerTest -f pom.xml`
Expected: PASS (mem: tests unaffected by the guard).
Run ops: `... -Dtest=OpsApplicationTest -f ops/pom.xml`
Expected: PASS (context loads).

- [ ] **Step 7: Commit**

```bash
git add ops/pom.xml ops/src src/main/java/uz/greenwhite/gateway/config/SettingsDbConfig.java
git commit -m "feat: ops sidecar module skeleton + gateway H2 AUTO_SERVER (mem-guarded)"
```

---

### Task 2: GatewayStatusService (HTTP health)

**Files:**
- Create: `ops/src/main/java/uz/greenwhite/ops/GatewayStatusService.java`
- Test: `ops/src/test/java/uz/greenwhite/ops/GatewayStatusServiceTest.java`

**Interfaces:**
- Produces: `boolean GatewayStatusService.isUp()` — true iff `GET {health-url}` returns 2xx within timeout; false on any error/timeout (never throws).

- [ ] **Step 1: Write the failing test**

`ops/src/test/java/uz/greenwhite/ops/GatewayStatusServiceTest.java`:
```java
package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GatewayStatusServiceTest {

    @Test
    void upWhenHealthReturns200() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("http://gw/health")).andRespond(withSuccess("{\"status\":\"UP\"}", org.springframework.http.MediaType.APPLICATION_JSON));
        GatewayStatusService svc = new GatewayStatusService(b.build(), "http://gw/health");
        assertTrue(svc.isUp());
    }

    @Test
    void downWhenHealthErrors() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("http://gw/health")).andRespond(withServerError());
        GatewayStatusService svc = new GatewayStatusService(b.build(), "http://gw/health");
        assertFalse(svc.isUp()); // 5xx → down, never throws
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... -Dtest=GatewayStatusServiceTest -f ops/pom.xml`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

`ops/src/main/java/uz/greenwhite/ops/GatewayStatusService.java`:
```java
package uz.greenwhite.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class GatewayStatusService {

    private final RestClient client;
    private final String healthUrl;

    public GatewayStatusService(RestClient.Builder builder,
                                @Value("${ops.gateway.health-url}") String healthUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(2000);
        this.client = builder.requestFactory(f).build();
        this.healthUrl = healthUrl;
    }

    /** True iff the gateway health endpoint answers 2xx within the timeout. Never throws. */
    public boolean isUp() {
        try {
            return client.get().uri(healthUrl).retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("Gateway health check failed: {}", e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... -Dtest=GatewayStatusServiceTest -f ops/pom.xml`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add ops/src/main/java/uz/greenwhite/ops/GatewayStatusService.java ops/src/test/java/uz/greenwhite/ops/GatewayStatusServiceTest.java
git commit -m "feat: GatewayStatusService — HTTP health up/down (never-throw)"
```

---

### Task 3: GatewayControlService (ProcessBuilder start/stop)

**Files:**
- Create: `ops/src/main/java/uz/greenwhite/ops/GatewayControlService.java`
- Test: `ops/src/test/java/uz/greenwhite/ops/GatewayControlServiceTest.java`

**Interfaces:**
- Produces:
  - `record ControlResult(boolean ok, int exitCode, String output)`
  - `ControlResult GatewayControlService.start()` / `stop()` — runs the configured command via ProcessBuilder; captures merged stdout/stderr; ok = exitCode==0; never throws (a failure to launch → ok=false, exitCode=-1, output=error message).

- [ ] **Step 1: Write the failing test**

`ops/src/test/java/uz/greenwhite/ops/GatewayControlServiceTest.java`:
```java
package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayControlServiceTest {

    private final boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
    private String echo(String s) { return windows ? "cmd /c echo " + s : "echo " + s; }

    @Test
    void startRunsConfiguredCommandAndCapturesOutput() {
        GatewayControlService svc = new GatewayControlService(echo("started-ok"), echo("stopped-ok"));
        GatewayControlService.ControlResult r = svc.start();
        assertTrue(r.ok());
        assertEquals(0, r.exitCode());
        assertTrue(r.output().contains("started-ok"));
    }

    @Test
    void failureToLaunchIsStructuredNotThrown() {
        GatewayControlService svc = new GatewayControlService("no-such-binary-xyz", "no-such-binary-xyz");
        GatewayControlService.ControlResult r = svc.stop();
        assertFalse(r.ok());
        assertEquals(-1, r.exitCode());
        assertNotNull(r.output());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... -Dtest=GatewayControlServiceTest -f ops/pom.xml`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

`ops/src/main/java/uz/greenwhite/ops/GatewayControlService.java`:
```java
package uz.greenwhite.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Runs the operator-configured (NOT user-supplied) start/stop command for the gateway.
 * Commands come from config only → no command injection from the UI.
 */
@Slf4j
@Service
public class GatewayControlService {

    public record ControlResult(boolean ok, int exitCode, String output) {}

    private final String startCommand;
    private final String stopCommand;

    public GatewayControlService(@Value("${ops.gateway.start-command}") String startCommand,
                                 @Value("${ops.gateway.stop-command}") String stopCommand) {
        this.startCommand = startCommand;
        this.stopCommand = stopCommand;
    }

    public ControlResult start() { return run(startCommand); }
    public ControlResult stop() { return run(stopCommand); }

    private ControlResult run(String command) {
        if (command == null || command.isBlank()) {
            return new ControlResult(false, -1, "command not configured");
        }
        try {
            // Split on whitespace — the command is a trusted config value (systemctl ... / a script path).
            ProcessBuilder pb = new ProcessBuilder(command.trim().split("\\s+"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new ControlResult(false, -1, out + "\n(command timed out after 30s)");
            }
            int code = p.exitValue();
            return new ControlResult(code == 0, code, out.toString());
        } catch (Exception e) {
            log.warn("Gateway control command failed: {}", e.getMessage());
            return new ControlResult(false, -1, "failed to run command: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... -Dtest=GatewayControlServiceTest -f ops/pom.xml`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add ops/src/main/java/uz/greenwhite/ops/GatewayControlService.java ops/src/test/java/uz/greenwhite/ops/GatewayControlServiceTest.java
git commit -m "feat: GatewayControlService — ProcessBuilder start/stop, never-throw, config-only commands"
```

---

### Task 4: LogTailService (tail gateway.log + SSE fan-out)

**Files:**
- Create: `ops/src/main/java/uz/greenwhite/ops/LogTailService.java`
- Test: `ops/src/test/java/uz/greenwhite/ops/LogTailServiceTest.java`

**Interfaces:**
- Produces:
  - `List<String> LogTailService.lastLines(int n)` — up to the last `n` lines of the log file (empty list if the file is absent).
  - `List<String> LogTailService.pollNewLines()` — lines appended since the last poll; resets to 0 if the file shrank (rotation). First call after construction primes the position to end-of-file (returns empty) so only NEW lines stream.
- Consumes: `ops.log-file` config path.

- [ ] **Step 1: Write the failing test**

`ops/src/test/java/uz/greenwhite/ops/LogTailServiceTest.java`:
```java
package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogTailServiceTest {

    @Test
    void lastLinesReturnsTail(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("gateway.log");
        Files.writeString(log, "l1\nl2\nl3\nl4\n");
        LogTailService svc = new LogTailService(log.toString());
        assertEquals(List.of("l3", "l4"), svc.lastLines(2));
    }

    @Test
    void lastLinesEmptyWhenFileAbsent(@TempDir Path dir) {
        LogTailService svc = new LogTailService(dir.resolve("nope.log").toString());
        assertTrue(svc.lastLines(10).isEmpty());
    }

    @Test
    void pollNewLinesStreamsAppendsAfterPrimingToEnd(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("gateway.log");
        Files.writeString(log, "old1\nold2\n");
        LogTailService svc = new LogTailService(log.toString());
        assertTrue(svc.pollNewLines().isEmpty()); // primes to EOF — old lines NOT re-streamed
        Files.writeString(log, "new1\nnew2\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(List.of("new1", "new2"), svc.pollNewLines());
    }

    @Test
    void pollResetsOnRotation(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("gateway.log");
        Files.writeString(log, "a\nb\n");
        LogTailService svc = new LogTailService(log.toString());
        svc.pollNewLines();                 // prime to EOF (pos=4)
        Files.writeString(log, "fresh\n");  // rotation: file truncated/replaced, smaller than pos
        assertEquals(List.of("fresh"), svc.pollNewLines()); // detects shrink → reads from 0
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... -Dtest=LogTailServiceTest -f ops/pom.xml`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

`ops/src/main/java/uz/greenwhite/ops/LogTailService.java`:
```java
package uz.greenwhite.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Tails the gateway log file: last-N snapshot + incremental new-line polling with rotation reset. */
@Slf4j
@Service
public class LogTailService {

    private static final int MAX_TAIL_BYTES = 256 * 1024;

    private final Path logFile;
    private long position = -1; // -1 = not yet primed

    public LogTailService(@Value("${ops.log-file}") String logFile) {
        this.logFile = Path.of(logFile);
    }

    /** Up to the last n lines; empty if the file is absent/unreadable. */
    public synchronized List<String> lastLines(int n) {
        if (!Files.isReadable(logFile)) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long len = raf.length();
            long from = Math.max(0, len - MAX_TAIL_BYTES);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String[] all = new String(buf, StandardCharsets.UTF_8).split("\n", -1);
            List<String> lines = new ArrayList<>();
            for (String s : all) if (!s.isEmpty()) lines.add(s);
            int start = Math.max(0, lines.size() - n);
            return new ArrayList<>(lines.subList(start, lines.size()));
        } catch (IOException e) {
            log.debug("lastLines read failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** New lines appended since the last poll. First call primes to EOF (returns empty). Resets on shrink. */
    public synchronized List<String> pollNewLines() {
        if (!Files.isReadable(logFile)) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long len = raf.length();
            if (position < 0 || position > len) {
                // first poll (prime to EOF) OR rotation (file shrank → read from 0)
                position = (position < 0) ? len : 0;
                if (position == len) return List.of();
            }
            raf.seek(position);
            byte[] buf = new byte[(int) (len - position)];
            raf.readFully(buf);
            position = len;
            String[] parts = new String(buf, StandardCharsets.UTF_8).split("\n", -1);
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < parts.length - 1; i++) lines.add(parts[i]); // trailing partial line ignored until newline
            // adjust position back to the start of any trailing partial line
            int lastNl = new String(buf, StandardCharsets.UTF_8).lastIndexOf('\n');
            if (lastNl >= 0) position = position - (buf.length - (lastNl + 1));
            return lines;
        } catch (IOException e) {
            log.debug("pollNewLines read failed: {}", e.getMessage());
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `... -Dtest=LogTailServiceTest -f ops/pom.xml`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add ops/src/main/java/uz/greenwhite/ops/LogTailService.java ops/src/test/java/uz/greenwhite/ops/LogTailServiceTest.java
git commit -m "feat: LogTailService — last-N + incremental poll with rotation reset"
```

---

### Task 5: Auth — shared-H2 admin DAO + OpsSecurityConfig + login

**Files:**
- Create: `ops/src/main/java/uz/greenwhite/ops/OpsDbConfig.java`
- Create: `ops/src/main/java/uz/greenwhite/ops/AdminAuthDao.java`
- Create: `ops/src/main/java/uz/greenwhite/ops/OpsSecurityConfig.java`
- Create: `ops/src/main/java/uz/greenwhite/ops/OpsAuthController.java`
- Test: `ops/src/test/java/uz/greenwhite/ops/OpsAuthTest.java`

**Interfaces:**
- Produces:
  - `boolean AdminAuthDao.verify(String username, String password)` — reads `admin_account` from the shared H2 (AUTO_SERVER), BCrypt-matches.
  - `OpsAuthController`: `POST /ops/api/login {username,password}` → 200 (session set) or 401; `POST /ops/api/logout` → 200.
  - `OpsSecurityConfig`: `/ops/api/login` + `GET /ops` permitAll; everything else `/ops/**` authenticated; CSRF via cookie (readable), session-based.
- Consumes: `ops.settings-db-path`.

- [ ] **Step 1: Write the failing test**

`ops/src/test/java/uz/greenwhite/ops/OpsAuthTest.java`:
```java
package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsauth;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsAuthTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate opsJdbc;

    void seedAdmin() {
        opsJdbc.execute("CREATE TABLE IF NOT EXISTS admin_account (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) UNIQUE, password_hash VARCHAR(200), updated_at TIMESTAMP, last_login_at TIMESTAMP)");
        opsJdbc.update("DELETE FROM admin_account");
        opsJdbc.update("INSERT INTO admin_account (username, password_hash) VALUES (?, ?)",
                "admin", new BCryptPasswordEncoder().encode("s3cret-pass"));
    }

    @Test
    void statusRequiresAuth() throws Exception {
        mvc.perform(get("/ops/api/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginSucceedsWithCorrectPassword() throws Exception {
        seedAdmin();
        mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"s3cret-pass\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        seedAdmin();
        mvc.perform(post("/ops/api/login").contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```
> The `opsJdbc` bean is the ops DataSource's JdbcTemplate (defined in OpsDbConfig, Step 3). CSRF: for the JSON login POST in tests, configure security so `/ops/api/login` is CSRF-exempt OR the test uses `.with(csrf())`. This plan makes `/ops/api/login` CSRF-exempt (it is the pre-session entry point); other POSTs (start/stop) require CSRF. Add `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;` only if a test needs it.

- [ ] **Step 2: Run test to verify it fails**

Run: `... -Dtest=OpsAuthTest -f ops/pom.xml`
Expected: FAIL — beans/endpoints missing.

- [ ] **Step 3: Implement the DB config + DAO**

`ops/src/main/java/uz/greenwhite/ops/OpsDbConfig.java`:
```java
package uz.greenwhite.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/** Connects to the SAME H2 settings DB as the gateway (shared admin_account), via AUTO_SERVER mixed-mode. */
@Configuration
public class OpsDbConfig {

    @Bean
    public DataSource opsDataSource(@Value("${ops.settings-db-path}") String dbPath) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        String autoServer = dbPath.startsWith("mem:") ? "" : ";AUTO_SERVER=TRUE";
        ds.setUrl("jdbc:h2:" + dbPath + ";MODE=LEGACY" + autoServer);
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    @Bean
    public JdbcTemplate opsJdbc(DataSource opsDataSource) {
        return new JdbcTemplate(opsDataSource);
    }
}
```

`ops/src/main/java/uz/greenwhite/ops/AdminAuthDao.java`:
```java
package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/** Verifies credentials against the shared admin_account table using BCrypt (same password as the admin UI). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthDao {

    private final JdbcTemplate opsJdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean verify(String username, String password) {
        if (username == null || password == null) return false;
        try {
            List<String> hashes = opsJdbc.query(
                    "SELECT password_hash FROM admin_account WHERE username = ?",
                    (rs, n) -> rs.getString("password_hash"), username);
            return !hashes.isEmpty() && encoder.matches(password, hashes.get(0));
        } catch (Exception e) {
            log.warn("Admin auth lookup failed (settings DB unreachable?): {}", e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 4: Implement security config + auth controller**

`ops/src/main/java/uz/greenwhite/ops/OpsSecurityConfig.java`:
```java
package uz.greenwhite.ops;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class OpsSecurityConfig {

    @Bean
    public SecurityFilterChain opsChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler(); // plain (no XOR) — avoids the SPA breakage seen in B1
        http
            .securityMatcher("/ops/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/ops", "/ops/", "/ops/index.html", "/ops/api/login").permitAll()
                .anyRequest().authenticated())
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrf)
                .ignoringRequestMatchers("/ops/api/login")) // pre-session entry point
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint(
                (req, res, ex) -> res.sendError(401)));
        return http.build();
    }
}
```

`ops/src/main/java/uz/greenwhite/ops/OpsAuthController.java`:
```java
package uz.greenwhite.ops;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ops/api")
@RequiredArgsConstructor
public class OpsAuthController {

    private final AdminAuthDao authDao;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest req,
                                      HttpServletRequest request, HttpServletResponse response) {
        if (!authDao.verify(req.username(), req.password())) {
            return ResponseEntity.status(401).build();
        }
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                req.username(), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response); // persist to session
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(Map.of("user", auth != null ? auth.getName() : ""));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `... -Dtest=OpsAuthTest -f ops/pom.xml`
Expected: PASS (3 tests). If the login test 403s on CSRF, confirm `/ops/api/login` is in `ignoringRequestMatchers` (it is).

- [ ] **Step 6: Commit**

```bash
git add ops/src/main/java/uz/greenwhite/ops/OpsDbConfig.java ops/src/main/java/uz/greenwhite/ops/AdminAuthDao.java ops/src/main/java/uz/greenwhite/ops/OpsSecurityConfig.java ops/src/main/java/uz/greenwhite/ops/OpsAuthController.java ops/src/test/java/uz/greenwhite/ops/OpsAuthTest.java
git commit -m "feat: ops auth — shared-H2 admin DAO + security chain + session login"
```

---

### Task 6: OpsController — status / start / stop / logs SSE

**Files:**
- Create: `ops/src/main/java/uz/greenwhite/ops/OpsController.java`
- Test: `ops/src/test/java/uz/greenwhite/ops/OpsControllerTest.java`

**Interfaces:**
- Consumes: `GatewayStatusService.isUp()`, `GatewayControlService.start()/stop()` + `ControlResult`, `LogTailService.lastLines(int)/pollNewLines()`.
- Produces:
  - `GET /ops/api/status` → `{ "up": boolean }`
  - `POST /ops/api/start` → `ControlResult` JSON; `POST /ops/api/stop` → `ControlResult` JSON
  - `GET /ops/api/logs/stream` → `SseEmitter` (text/event-stream): emits last 200 lines, then new lines every ~1s.

- [ ] **Step 1: Write the failing test**

`ops/src/test/java/uz/greenwhite/ops/OpsControllerTest.java`:
```java
package uz.greenwhite.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsctl;DB_CLOSE_DELAY=-1",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
@AutoConfigureMockMvc
class OpsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean GatewayStatusService status;
    @MockBean GatewayControlService control;

    @Test
    void statusRequiresAuth() throws Exception {
        mvc.perform(get("/ops/api/status")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void statusReportsUp() throws Exception {
        when(status.isUp()).thenReturn(true);
        mvc.perform(get("/ops/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.up").value(true));
    }

    @Test
    @WithMockUser
    void stopInvokesControlService() throws Exception {
        when(control.stop()).thenReturn(new GatewayControlService.ControlResult(true, 0, "stopped"));
        mvc.perform(post("/ops/api/stop").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `... -Dtest=OpsControllerTest -f ops/pom.xml`
Expected: FAIL — controller missing.

- [ ] **Step 3: Implement**

`ops/src/main/java/uz/greenwhite/ops/OpsController.java`:
```java
package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RestController
@RequestMapping("/ops/api")
@RequiredArgsConstructor
public class OpsController {

    private final GatewayStatusService statusService;
    private final GatewayControlService controlService;
    private final LogTailService logTailService;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("up", statusService.isUp());
    }

    @PostMapping("/start")
    public GatewayControlService.ControlResult start() {
        return controlService.start();
    }

    @PostMapping("/stop")
    public GatewayControlService.ControlResult stop() {
        return controlService.stop();
    }

    @GetMapping("/logs/stream")
    public SseEmitter streamLogs() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            for (String line : logTailService.lastLines(200)) {
                emitter.send(SseEmitter.event().data(line));
            }
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
            return emitter;
        }
        emitters.add(emitter);
        return emitter;
    }

    /** Push newly-appended log lines to all connected clients (~1s cadence). */
    @Scheduled(fixedDelay = 1000)
    public void pushNewLines() {
        if (emitters.isEmpty()) { logTailService.pollNewLines(); return; } // keep position current
        List<String> newLines = logTailService.pollNewLines();
        if (newLines.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                for (String line : newLines) emitter.send(SseEmitter.event().data(line));
            } catch (Exception e) {
                emitter.complete();
                emitters.remove(emitter);
            }
        }
    }
}
```

> Enable scheduling: add `@EnableScheduling` to `OpsApplication` (Task 1 file). Note this in the report; if not already present, add it now.

- [ ] **Step 4: Enable scheduling**

`ops/src/main/java/uz/greenwhite/ops/OpsApplication.java` — add the annotation:
```java
import org.springframework.scheduling.annotation.EnableScheduling;
// ...
@SpringBootApplication
@EnableScheduling
public class OpsApplication { ... }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `... -Dtest=OpsControllerTest -f ops/pom.xml`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add ops/src/main/java/uz/greenwhite/ops/OpsController.java ops/src/main/java/uz/greenwhite/ops/OpsApplication.java ops/src/test/java/uz/greenwhite/ops/OpsControllerTest.java
git commit -m "feat: OpsController — status/start/stop + log SSE fan-out"
```

---

### Task 7: Ops console HTML page + systemd unit + docs + build integration

**Files:**
- Create: `ops/src/main/resources/static/ops/index.html`
- Create: `ops/qosid-ops.service` (systemd unit template)
- Modify: `ai-context/ARCHITECTURE.md`, `ai-context/CONFIGURATION.md`, `CLAUDE.md`
- Test: manual live smoke (documented) + `ops/` full suite green

**Interfaces:**
- Consumes: `/ops/api/{login,status,start,stop,logs/stream}` (Tasks 5-6).

- [ ] **Step 1: Create the ops console page**

`ops/src/main/resources/static/ops/index.html` — a self-contained page (vanilla JS). Serve it at `/ops` via a tiny view mapping OR rely on Spring's static resource serving of `/ops/index.html`. Add a controller mapping so `GET /ops` returns this page:

First add to `OpsController` (Task 6 file) a page mapping — but that controller is `@RestController`; instead create a minimal `@Controller` for the page. Create `ops/src/main/java/uz/greenwhite/ops/OpsPageController.java`:
```java
package uz.greenwhite.ops;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OpsPageController {
    @GetMapping("/ops")
    public String page() { return "forward:/ops/index.html"; }
}
```

`ops/src/main/resources/static/ops/index.html`:
```html
<!doctype html>
<html lang="uz">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>Qosid Ops konsoli</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; background: #0f1720; color: #e6edf3; }
  header { display: flex; align-items: center; gap: 16px; padding: 12px 20px; background: #17212b; border-bottom: 1px solid #26303b; }
  .badge { padding: 4px 10px; border-radius: 12px; font-weight: 600; }
  .up { background: #14532d; color: #86efac; } .down { background: #5b1a1a; color: #fca5a5; }
  button { padding: 8px 16px; border: 0; border-radius: 6px; font-weight: 600; cursor: pointer; }
  .stop { background: #dc2626; color: #fff; } .start { background: #16a34a; color: #fff; }
  #log { margin: 16px 20px; height: calc(100vh - 140px); overflow: auto; background: #0b1016; border: 1px solid #26303b; border-radius: 8px; padding: 12px; font-family: ui-monospace, monospace; font-size: 12px; white-space: pre-wrap; }
  .err { color: #fca5a5; } .warn { color: #fcd34d; }
  #login { max-width: 320px; margin: 80px auto; padding: 24px; background: #17212b; border-radius: 8px; }
  #login input { width: 100%; padding: 8px; margin: 6px 0; box-sizing: border-box; }
  .hidden { display: none; }
  .toolbar { display: flex; gap: 8px; align-items: center; margin: 12px 20px 0; }
</style>
</head>
<body>
<div id="login">
  <h3>Qosid Ops — kirish</h3>
  <input id="u" placeholder="Login" autocomplete="username"/>
  <input id="p" type="password" placeholder="Parol" autocomplete="current-password"/>
  <button class="start" onclick="login()">Kirish</button>
  <div id="loginErr" class="err"></div>
</div>

<div id="app" class="hidden">
  <header>
    <strong>Qosid Ops konsoli</strong>
    <span id="statusBadge" class="badge down">tekshirilmoqda…</span>
    <button id="toggleBtn" onclick="toggle()"></button>
    <span id="ctlMsg"></span>
  </header>
  <div class="toolbar">
    <button onclick="paused=!paused; this.textContent = paused ? 'Davom' : 'Pauza'">Pauza</button>
    <button onclick="document.getElementById('log').textContent=''">Tozala</button>
    <small>gateway.log — real-time</small>
  </div>
  <div id="log"></div>
</div>

<script>
let paused = false, up = null, es = null;
function csrf() { const m = document.cookie.match(/XSRF-TOKEN=([^;]+)/); return m ? decodeURIComponent(m[1]) : ''; }
async function login() {
  const r = await fetch('/ops/api/login', { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({ username: u.value, password: p.value }) });
  if (r.ok) { document.getElementById('login').classList.add('hidden'); document.getElementById('app').classList.remove('hidden'); start(); }
  else document.getElementById('loginErr').textContent = 'Login yoki parol xato';
}
function start() { pollStatus(); setInterval(pollStatus, 3000); openStream(); }
async function pollStatus() {
  try {
    const r = await fetch('/ops/api/status');
    if (r.status === 401) { location.reload(); return; }
    const j = await r.json(); setStatus(j.up);
  } catch { setStatus(false); }
}
function setStatus(u) {
  up = u;
  const b = document.getElementById('statusBadge'), t = document.getElementById('toggleBtn');
  b.textContent = u ? '● ISHLAYAPTI' : '● O‘CHIQ';
  b.className = 'badge ' + (u ? 'up' : 'down');
  t.textContent = u ? 'To‘xtatish' : 'Ishga tushirish';
  t.className = u ? 'stop' : 'start';
}
async function toggle() {
  const action = up ? 'stop' : 'start';
  if (action === 'stop' && !confirm('Gateway’ni to‘xtatasizmi?')) return;
  document.getElementById('ctlMsg').textContent = '…';
  const r = await fetch('/ops/api/' + action, { method: 'POST', headers: { 'X-XSRF-TOKEN': csrf() } });
  const j = await r.json();
  document.getElementById('ctlMsg').textContent = j.ok ? 'OK' : ('Xato: ' + (j.output || '').slice(0, 200));
  setTimeout(pollStatus, 1500);
}
function openStream() {
  es = new EventSource('/ops/api/logs/stream');
  es.onmessage = (e) => {
    const log = document.getElementById('log');
    const div = document.createElement('div');
    if (/\bERROR\b/.test(e.data)) div.className = 'err'; else if (/\bWARN\b/.test(e.data)) div.className = 'warn';
    div.textContent = e.data;
    log.appendChild(div);
    if (!paused) log.scrollTop = log.scrollHeight;
    while (log.childNodes.length > 2000) log.removeChild(log.firstChild); // bound memory
  };
  es.onerror = () => { /* EventSource auto-reconnects */ };
}
</script>
</body>
</html>
```

- [ ] **Step 2: Create the systemd unit template**

`ops/qosid-ops.service`:
```ini
[Unit]
Description=Qosid Ops Console (sidecar)
After=network.target

[Service]
Type=simple
User=qosid
WorkingDirectory=/opt/services/qosid-flow
ExecStart=/usr/bin/java -jar /opt/services/qosid-flow/ops/qosid-ops.jar
Environment=OPS_GATEWAY_START_COMMAND=/usr/bin/systemctl start gateway-service
Environment=OPS_GATEWAY_STOP_COMMAND=/usr/bin/systemctl stop gateway-service
Environment=OPS_LOG_FILE=/opt/services/qosid-flow/logs/gateway.log
Environment=OPS_SETTINGS_DB_PATH=/opt/services/qosid-flow/data/settings
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```
> Deploy note (put in docs): the `qosid` user needs permission to run `systemctl start/stop gateway-service` — add a polkit rule or a sudoers NOPASSWD entry scoped to exactly those two commands. Bind 8092 behind nginx / firewall.

- [ ] **Step 3: Run the full ops suite**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" "/c/Program Files/JetBrains/IntelliJ IDEA 2025.3.3/plugins/maven/lib/maven3/bin/mvn.cmd" -q test -f ops/pom.xml`
Expected: PASS — all ops tests (OpsApplication, GatewayStatus, GatewayControl, LogTail, OpsAuth, OpsController).

- [ ] **Step 4: Update docs**

- `ai-context/ARCHITECTURE.md`: new "Ops console (sidecar)" section — separate `qosid-ops` JVM (:8092, own systemd unit), toggle START/STOP via configured command, HTTP-health status, log SSE tail, shared-H2 auth (AUTO_SERVER), independent of the gateway.
- `ai-context/CONFIGURATION.md`: `ops.*` config keys (port, gateway.health-url, gateway.start-command/stop-command, log-file, settings-db-path) + the `SettingsDbConfig` AUTO_SERVER note.
- `CLAUDE.md`: mention the ops sidecar in the architecture overview / a one-line note that `qosid-ops` (:8092) controls the gateway JVM and streams logs, deployed as its own systemd unit; note the deploy requirement (systemctl permission).

- [ ] **Step 5: Commit**

```bash
git add ops/src/main/resources/static/ops/index.html ops/src/main/java/uz/greenwhite/ops/OpsPageController.java ops/qosid-ops.service ai-context/ARCHITECTURE.md ai-context/CONFIGURATION.md CLAUDE.md
git commit -m "feat: ops console HTML page + systemd unit + docs"
```

- [ ] **Step 6: Live smoke (manual, documented in report)**

1. Build: `... package -f ops/pom.xml -DskipTests` → `ops/target/qosid-ops.jar`.
2. Run locally with env: `OPS_GATEWAY_START_COMMAND`/`STOP_COMMAND` set to local scripts (start = launch the gateway JAR; stop = kill port 8090), `OPS_LOG_FILE=./logs/gateway.log`, `OPS_SETTINGS_DB_PATH=./data/settings`.
3. Open `http://localhost:8092/ops` → login with admin creds → status shows ISHLAYAPTI (gateway up).
4. Click "To'xtatish" → confirm → status → O'CHIQ; log window shows shutdown lines streaming.
5. Click "Ishga tushirish" → status → ISHLAYAPTI; startup lines stream.
Record the outcome in the report.

---

## Self-Review (writing-plans)

**Spec coverage:**
- §3 architecture (sidecar, shared-H2 AUTO_SERVER, HTTP status, configured control) → Tasks 1,2,3,5 ✅
- §4 backend components (status/control/logtail/controller/security) → Tasks 2,3,4,5,6 ✅
- §5 UI (toggle + log SSE, vanilla HTML) → Task 7 ✅
- §6 security (session auth, no command injection, CSRF) → Tasks 5,6 ✅
- §7 deploy (systemd unit, config, permission) → Task 7 ✅
- §8 testing → each task TDD + Task 7 live smoke ✅

**Placeholder scan:** No TBD/TODO. LogTailService partial-line handling is fully coded. HTML page is complete. systemd unit complete.

**Type consistency:** `ControlResult(ok,exitCode,output)` — Task 3 record, consumed verbatim in Task 6 controller + tests. `GatewayStatusService.isUp()` — Task 2 → Task 6. `LogTailService.lastLines(int)`/`pollNewLines()` — Task 4 → Task 6. `AdminAuthDao.verify(u,p)` — Task 5 → OpsAuthController. Endpoints `/ops/api/{login,status,start,stop,logs/stream}` consistent between controllers (Tasks 5,6) and the HTML page (Task 7). `opsJdbc` bean — Task 5 OpsDbConfig, used in Task 5 test.

## Execution Handoff

Plan `docs/superpowers/plans/2026-07-16-b-ops-console.md` ga saqlandi.
