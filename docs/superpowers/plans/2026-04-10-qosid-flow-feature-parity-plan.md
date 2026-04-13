# Qosid-Flow Feature Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Old messagebroker'dagi yetishmayotgan funksionallarni qosid-flow'ga qo'shish va texnik yaxshilashlarni amalga oshirish.

**Architecture:** Modular layered approach — mavjud E1-E5 pipeline buzilmaydi. Yangi funksionallar (API Layer, Storage, Cross-cutting) alohida package/layer sifatida qo'shiladi. REST API yangi entry point, RocksDB response storage, S3 file upload, callback system.

**Tech Stack:** Java 21, Spring Boot 3.3.0, Kafka, Redis, RocksDB, MinIO, Cloudflare R2 (AWS SDK), Resilience4j, WebClient (Reactor Netty)

**Design Spec:** `docs/superpowers/specs/2026-04-10-qosid-flow-feature-parity-design.md`

---

## File Map

### Yangi fayllar yaratiladi:

```
src/main/java/uz/greenwhite/gateway/
├── api/                                    # API Layer (yangi package)
│   ├── ProxyController.java                # POST/GET /qosid/queue/**
│   ├── StatusController.java               # GET /status/{id}, /result/{id}
│   ├── CallbackController.java             # POST /qosid/api/v1/callback
│   └── model/
│       ├── ProxyResponse.java              # 202 ACCEPTED response model
│       ├── StatusResponse.java             # Status endpoint response model
│       └── CallbackPayload.java            # Callback request/response model
├── auth/                                   # Authorization (yangi package)
│   ├── AuthorizationProvider.java          # Interface
│   ├── AuthContext.java                    # Request context for auth
│   ├── AuthResult.java                     # Auth result (allowed/denied)
│   └── NoOpAuthorizationProvider.java      # Default — no auth
├── storage/                                # RocksDB storage (yangi package)
│   ├── RocksDBClient.java                  # RocksDB operations
│   ├── RocksDBConfig.java                  # Bean configuration
│   ├── RocksDBProperties.java              # Config properties
│   ├── ResponseStorageService.java         # Response CRUD + cleanup
│   └── ResponseMetadata.java              # Metadata model
├── file/                                   # File upload (yangi package)
│   ├── FileService.java                    # Upload orchestrator
│   ├── FileProperties.java                 # S3 config properties
│   ├── ContentTypeValidator.java           # MIME whitelist
│   ├── ServletFileUploadEntity.java        # Multipart parser
│   └── s3/
│       ├── MinIOClient.java                # MinIO S3 client
│       └── CloudflareR2Client.java         # R2 S3 client
├── callback/                               # Callback system (yangi package)
│   ├── CallbackSender.java                 # HTTP callback with retry
│   └── CallbackService.java                # Callback orchestrator
└── exception/                              # Exception handling (yangi package)
    ├── GlobalExceptionHandler.java         # @RestControllerAdvice
    ├── ErrorResponse.java                  # Standard error response model
    ├── AuthorizationDeniedException.java   # 403 exception
    └── RequestValidationException.java     # 400 exception
```

### Mavjud fayllar o'zgartiriladi:

```
pom.xml                                     # Yangi dependency'lar
src/main/resources/application.yml          # Yangi config parametrlari

src/main/java/uz/greenwhite/gateway/
├── config/
│   ├── HttpClientConfig.java               # Connection pool sozlash
│   ├── HttpProperties.java                 # Yangi pool field'lar
│   ├── ThreadPoolConfig.java               # Configurable multiplier/queue
│   └── ConcurrencyProperties.java          # threadPoolMultiplier, threadPoolQueueCapacity
├── model/
│   ├── RequestState.java                   # source, callbackUrl, hasResult field'lar
│   └── kafka/
│       ├── RequestMessage.java             # source, callbackUrl, fileReferences, clientIp, contentType
│       └── ResponseMessage.java            # body: String→byte[], callbackUrl, rocksDbKey, source
├── kafka/consumer/
│   ├── RequestConsumer.java                # byte[] body support
│   └── ResponseConsumer.java               # RocksDB save, callback trigger, source routing
├── http/
│   └── HttpRequestService.java             # byte[] body, content-type validation
├── oracle/
│   └── RequestPuller.java                  # source="ORACLE" qo'yish
├── state/
│   └── RequestStateService.java            # Yangi field'lar support
└── metrics/
    └── GatewayMetrics.java                 # API, RocksDB, callback metrikalari
```

---

## Feature 1: Dependencies va Server Tuning

**Maqsad:** Yangi kutubxonalarni qo'shish, server sozlamalarini old metrikalariga moslashtirish.

### Task 1.1: pom.xml ga yangi dependency'lar qo'shish

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Dependency'lar qo'shish**

**Prompt:**
```
qosid-flow loyihasining pom.xml faylini o'qi. Quyidagi dependency'larni <dependencies> bo'limiga qo'sh:

1. org.rocksdb:rocksdbjni:9.7.3 — Response body storage uchun
2. io.minio:minio:8.5.12 — MinIO S3 client (fayl yuklash)
3. software.amazon.awssdk:s3:2.27.24 — Cloudflare R2 client
4. org.springframework.retry:spring-retry — Callback retry (@Retryable)
5. org.springframework.boot:spring-boot-starter-aop — @Retryable ishlashi uchun (agar yo'q bo'lsa)

Eslatma: Mavjud dependency'larni o'zgartirma, faqat yangilarini qo'sh. spring-boot-starter-aop allaqachon bor bo'lishi mumkin — tekshirib qo'sh.
```

- [ ] **Step 2: Build test**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add RocksDB, MinIO, AWS S3, spring-retry dependencies"
```

---

### Task 1.2: Server tuning va application.yml yangilash

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Server va yangi config parametrlarini qo'shish**

**Prompt:**
```
qosid-flow loyihasining src/main/resources/application.yml faylini o'qi.

Quyidagi o'zgarishlarni qil:

1. server: bo'limiga compression va tomcat tuning qo'sh (old metrikalaridan):
   server:
     shutdown: graceful
     compression:
       enabled: true
       mime-types: text/html,text/xml,text/plain,text/css,application/javascript,application/json
       min-response-size: 2KB
     tomcat:
       accept-count: ${TOMCAT_ACCEPT_COUNT:1000}
       max-connections: ${TOMCAT_MAX_CONNECTIONS:10000}
       connection-timeout: ${TOMCAT_CONNECTION_TIMEOUT:20s}
       max-http-form-post-size: 2MB
       threads:
         max: ${TOMCAT_MAX_THREADS:2000}

2. spring: bo'limiga qo'sh:
   spring:
     lifecycle:
       timeout-per-shutdown-phase: 30s
     servlet:
       multipart:
         max-file-size: 50MB
         max-request-size: 52MB
         file-size-threshold: 128KB
         resolve-lazily: true

3. gateway: bo'limiga yangi section'lar qo'sh:
   gateway:
     target:
       base-url: ${TARGET_BASE_URL:http://localhost:8081/b}
     proxy:
       prefix: /qosid/queue
       context-path: /qosid
     response:
       threshold-size: ${RESPONSE_THRESHOLD_SIZE:1024}
     rocksdb:
       path: ${ROCKSDB_PATH:./data/rocksdb}
       response-retention-days: ${ROCKSDB_RETENTION_DAYS:7}
       cleanup-cron: "0 0 0 * * *"
     s3:
       minio:
         enabled: ${MINIO_ENABLED:false}
         endpoint: ${MINIO_ENDPOINT:}
         access-key: ${MINIO_ACCESS_KEY:}
         secret-key: ${MINIO_SECRET_KEY:}
         bucket: ${MINIO_BUCKET:gateway-files}
       cloudflare-r2:
         enabled: ${R2_ENABLED:false}
         endpoint: ${R2_ENDPOINT:}
         access-key: ${R2_ACCESS_KEY:}
         secret-key: ${R2_SECRET_KEY:}
         temp-bucket: ${R2_TEMP_BUCKET:}
         permanent-bucket: ${R2_PERMANENT_BUCKET:}
         link-expiration-hours: ${R2_LINK_EXPIRY:24}
     security:
       content-types:
         - application/json
         - application/javascript
         - application/x-www-form-urlencoded
         - text/plain
         - multipart/form-data
         - image/jpeg
         - image/png
         - image/gif
         - image/webp
         - image/svg+xml
         - image/tiff
         - image/bmp
         - image/x-icon
         - image/heic
         - image/heif
         - audio/mpeg
         - audio/wav
         - audio/ogg
         - audio/midi
         - audio/x-m4a
         - audio/webm
         - audio/aac
         - video/mp4
         - video/mpeg
         - video/webm
         - video/quicktime
         - video/x-msvideo
         - video/x-matroska
         - video/3gpp

4. gateway.http: bo'limiga connection pool parametrlarini qo'sh:
     http:
       max-connections: ${HTTP_MAX_CONNECTIONS:500}
       max-idle-time-ms: ${HTTP_MAX_IDLE_TIME:60000}
       max-life-time-ms: ${HTTP_MAX_LIFE_TIME:300000}
       pending-acquire-timeout-ms: ${HTTP_PENDING_ACQUIRE:45000}

5. gateway.concurrency: bo'limiga qo'sh:
     concurrency:
       thread-pool-multiplier: ${THREAD_POOL_MULTIPLIER:2}
       thread-pool-queue-capacity: ${THREAD_POOL_QUEUE:50}

Mavjud parametrlarni o'zgartirma. Faqat yangilarini qo'sh. YAML indentation'ga ehtiyot bo'l.
```

- [ ] **Step 2: Build test**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: add server tuning, S3, RocksDB, content-type, proxy settings"
```

---

### Task 1.3: Config property class'larini yangilash

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/config/HttpProperties.java`
- Modify: `src/main/java/uz/greenwhite/gateway/config/ConcurrencyProperties.java`
- Modify: `src/main/java/uz/greenwhite/gateway/config/HttpClientConfig.java`
- Modify: `src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java`

- [ ] **Step 1: HttpProperties va HttpClientConfig yangilash**

**Prompt:**
```
qosid-flow loyihasida 2 ta faylni o'zgartirishim kerak:

1. src/main/java/uz/greenwhite/gateway/config/HttpProperties.java ni o'qi.
   Yangi field'lar qo'sh:
   - int maxConnections (default 500)
   - long maxIdleTimeMs (default 60000)
   - long maxLifeTimeMs (default 300000)
   - long pendingAcquireTimeoutMs (default 45000)
   @ConfigurationProperties prefix'i "gateway.http" — o'zgarmaydi.

2. src/main/java/uz/greenwhite/gateway/config/HttpClientConfig.java ni o'qi.
   WebClient bean'ini yangilab, Reactor Netty connection pool'ni aniq sozla:
   - ConnectionProvider.builder("gateway-http-pool")
     .maxConnections(httpProperties.getMaxConnections())
     .maxIdleTime(Duration.ofMillis(httpProperties.getMaxIdleTimeMs()))
     .maxLifeTime(Duration.ofMillis(httpProperties.getMaxLifeTimeMs()))
     .pendingAcquireTimeout(Duration.ofMillis(httpProperties.getPendingAcquireTimeoutMs()))
     .build()
   - HttpClient.create(connectionProvider) orqali client yarat
   - Mavjud timeout logikasini saqla, faqat connection pool qo'sh
   
   Import'lar: reactor.netty.resources.ConnectionProvider, java.time.Duration
```

- [ ] **Step 2: ConcurrencyProperties va ThreadPoolConfig yangilash**

**Prompt:**
```
qosid-flow loyihasida 2 ta faylni o'zgartirishim kerak:

1. src/main/java/uz/greenwhite/gateway/config/ConcurrencyProperties.java ni o'qi.
   Yangi field'lar qo'sh:
   - int threadPoolMultiplier (default 2)
   - int threadPoolQueueCapacity (default 50)

2. src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java ni o'qi.
   Hardcoded qiymatlarni properties'dan oladigan qil:
   - executor.setMaxPoolSize(concurrencyProperties.getMaxConcurrency() * concurrencyProperties.getThreadPoolMultiplier())
     // hozir maxConcurrency * 2 — o'rniga multiplier'dan
   - executor.setQueueCapacity(concurrencyProperties.getThreadPoolQueueCapacity())
     // hozir hardcoded 50 — o'rniga property'dan
   - Qolgan logikani o'zgartirma
```

- [ ] **Step 3: Build test**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/config/
git commit -m "config: add connection pool settings, configurable thread pool"
```

---

## Feature 2: Model o'zgarishlari (RequestMessage, ResponseMessage, RequestState)

**Maqsad:** Pipeline model'lariga yangi field'lar qo'shish — API layer va storage uchun asos.

### Task 2.1: RequestMessage yangi field'lar

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/model/kafka/RequestMessage.java`

- [ ] **Step 1: Yangi field'lar qo'shish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/model/kafka/RequestMessage.java ni o'qi.

Mavjud field'larga quyidagilarni qo'sh (mavjud field'larni o'zgartirma):

    private String source;              // "ORACLE" | "API"
    
    @JsonProperty("callback_url")
    private String callbackUrl;         // X-Callback-Url header'dan (nullable)
    
    @JsonProperty("file_references")
    private List<String> fileReferences; // S3'ga yuklangan fayllar SHA ro'yxati (nullable)
    
    @JsonProperty("client_ip")
    private String clientIp;            // Statistika uchun
    
    @JsonProperty("content_type")
    private String contentType;         // Original content-type

Import qo'sh: java.util.List

getCompositeId() methodida o'zgarish: agar source "API" bo'lsa, compositeId = "api:" + UUID formatda bo'lishi kerak. Lekin hozir bu method mavjud companyId:requestId qaytaradi — uni o'zgartirma. API so'rovlar uchun compositeId ProxyController'da yaratiladi.
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/model/kafka/RequestMessage.java
git commit -m "model: add source, callbackUrl, fileReferences, clientIp, contentType to RequestMessage"
```

---

### Task 2.2: ResponseMessage — body String→byte[] va yangi field'lar

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/model/kafka/ResponseMessage.java`

- [ ] **Step 1: ResponseMessage yangilash**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/model/kafka/ResponseMessage.java ni o'qi.

Quyidagi o'zgarishlarni qil:

1. body field'ni String dan byte[] ga o'zgartir:
   private byte[] body;  // was: private String body;

2. Yangi field'lar qo'sh:
   private String callbackUrl;    // RequestMessage'dan ko'chiriladi
   private String rocksDbKey;     // RocksDB'da response body key
   private String source;         // "ORACLE" | "API"

3. isSuccess() va isRetryable() method'larini o'zgartirma — ular body'ga bog'liq emas.

MUHIM: body tipini String'dan byte[] ga o'zgartirish boshqa fayllarda compile error chiqaradi. Bu keyingi task'larda fix qilinadi. Hozir faqat ResponseMessage'ni o'zgartir.
```

- [ ] **Step 2: Compile errors ro'yxatini aniqlash**

Run: `mvn clean compile -DskipTests 2>&1 | grep -i "error" | head -30`
Expected: HttpRequestService, ResponseConsumer, BiruniClient'da compile errors (body String→byte[] o'zgarishi sababli)

- [ ] **Step 3: Commit (compile error'lar bilan — keyingi task'da fix bo'ladi)**

```bash
git add src/main/java/uz/greenwhite/gateway/model/kafka/ResponseMessage.java
git commit -m "model: change ResponseMessage.body to byte[], add callbackUrl, rocksDbKey, source"
```

---

### Task 2.3: ResponseMessage byte[] o'zgarishiga moslashtirish

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java`
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java`
- Modify: `src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java`
- Modify: `src/main/java/uz/greenwhite/gateway/model/ResponseSaveRequest.java`

- [ ] **Step 1: Barcha compile error'larni fix qilish**

**Prompt:**
```
ResponseMessage.body tipi String'dan byte[] ga o'zgartirildi. Endi compile error'larni fix qilish kerak.

Quyidagi fayllarni o'qi va fix qil:

1. src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java — body qaytaradigan joylarni fix qil:
   - buildSuccessResponse(): response body'ni byte[] sifatida ol. WebClient'dan .bodyToMono(byte[].class) ishlatib natijani ol (hozir String.class bo'lishi mumkin). body field'ga byte[] qo'y.
   - buildErrorResponse(), buildCircuitBreakerResponse(), buildOAuth2ErrorResponse(): errorMessage'ni body'ga yozadigan joylarni errorMessage.getBytes(StandardCharsets.UTF_8) ga o'zgartir.
   - Agar body hech qayerda set qilinmasa, null qoldirib ket.

2. src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java — buildSaveRequest() va saveErrorResponse() method'larida body ishlatilsa, byte[]→String conversion kerak bo'lishi mumkin: new String(body, StandardCharsets.UTF_8).

3. src/main/java/uz/greenwhite/gateway/oracle/BiruniClient.java — saveResponse() method'ida ResponseSaveRequest'da body String bo'lishi kerak (Oracle'ga String yuboriladi). ResponseSaveRequest modelida body String qoladi — ResponseConsumer'da byte[]→String conversion qilish kerak.

4. src/main/java/uz/greenwhite/gateway/model/ResponseSaveRequest.java — o'qi. Agar body field String bo'lsa, o'zgartirma (Oracle'ga String kerak).

Barcha import'larni fix qil. java.nio.charset.StandardCharsets kerak bo'ladi.

MUHIM QOIDA: @ai-context/SAFETY-RULES.md dagi qoidalarga rioya qil — retry logikasini, MANUAL ack'ni, idempotency logikasini o'zgartirma. Faqat body type conversion'ni fix qil.
```

- [ ] **Step 2: Build test**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/
git commit -m "fix: adapt pipeline to ResponseMessage byte[] body type"
```

---

### Task 2.4: RequestState yangi field'lar

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/model/RequestState.java`

- [ ] **Step 1: RequestState yangilash**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/model/RequestState.java ni o'qi.

Yangi field'lar qo'sh:
    private String source;        // "ORACLE" | "API"
    private String callbackUrl;   // nullable
    private boolean hasResult;    // RocksDB'da natija bormi

Mavjud field'larni o'zgartirma.
```

- [ ] **Step 2: RequestPuller'da source="ORACLE" qo'yish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/oracle/RequestPuller.java ni o'qi.

processRequests() yoki sendToKafkaWithRetry() method'ida, Kafka'ga yuborishdan oldin request message'ga source qo'y:
    request.setSource("ORACLE");

Boshqa hech narsani o'zgartirma.
```

- [ ] **Step 3: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/model/RequestState.java src/main/java/uz/greenwhite/gateway/oracle/RequestPuller.java
git commit -m "model: add source, callbackUrl, hasResult to RequestState; set source=ORACLE in puller"
```

---

## Feature 3: RocksDB Response Storage

**Maqsad:** Response body'ni disk-based storage'da saqlash, 7 kun retention, cleanup cron.

### Task 3.1: RocksDB properties va config

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/storage/RocksDBProperties.java`
- Create: `src/main/java/uz/greenwhite/gateway/storage/RocksDBConfig.java`
- Create: `src/main/java/uz/greenwhite/gateway/storage/ResponseMetadata.java`

- [ ] **Step 1: RocksDB infrastructure yaratish**

**Prompt:**
```
qosid-flow loyihasida RocksDB response storage uchun infra fayllarni yarat.

1. src/main/java/uz/greenwhite/gateway/storage/RocksDBProperties.java:
   @Getter @Setter @Component
   @ConfigurationProperties(prefix = "gateway.rocksdb")
   - String path (default "./data/rocksdb")
   - int responseRetentionDays (default 7)
   - String cleanupCron (default "0 0 0 * * *")

2. src/main/java/uz/greenwhite/gateway/storage/RocksDBConfig.java:
   @Configuration class
   - @Bean RocksDB rocksDB(RocksDBProperties props):
     RocksDB.loadLibrary() chaqir
     Options options = new Options().setCreateIfMissing(true)
     Directory mavjud bo'lmasa yarat (Files.createDirectories)
     return RocksDB.open(options, props.getPath())
   - @PreDestroy: RocksDB.close()

3. src/main/java/uz/greenwhite/gateway/storage/ResponseMetadata.java:
   @Data @Builder @NoArgsConstructor @AllArgsConstructor
   - int httpStatus
   - String contentType
   - long contentLength
   - long savedAt (System.currentTimeMillis())

Package: uz.greenwhite.gateway.storage
```

- [ ] **Step 2: Build test**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/storage/
git commit -m "feat: add RocksDB configuration and properties"
```

---

### Task 3.2: RocksDB client va ResponseStorageService

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/storage/RocksDBClient.java`
- Create: `src/main/java/uz/greenwhite/gateway/storage/ResponseStorageService.java`

- [ ] **Step 1: RocksDB client yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/storage/RocksDBClient.java yarat:

@Slf4j @Component class, RocksDB bean'ini inject qiladi.

Key prefix'lar:
  RESPONSE_PREFIX = "response:"
  RESPONSE_META_PREFIX = "response_meta:"

Method'lar:
  void putResponse(String compositeId, byte[] body) — RocksDB'ga yozish
  byte[] getResponse(String compositeId) — body o'qish, topilmasa null
  void putResponseMetadata(String compositeId, ResponseMetadata meta) — ObjectMapper bilan serialize qilib yozish
  ResponseMetadata getResponseMetadata(String compositeId) — deserialize qilib o'qish, topilmasa null
  void deleteResponse(String compositeId) — body + meta o'chirish
  void iterateMetadata(java.util.function.BiConsumer<String, ResponseMetadata> consumer) — barcha meta'larni iterate qilish (cleanup uchun)

ObjectMapper field qo'sh (new ObjectMapper() yoki @Autowired).
Barcha RocksDB operatsiyalarida try-catch bo'lsin, RocksDBException loglash.
```

- [ ] **Step 2: ResponseStorageService yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/storage/ResponseStorageService.java yarat:

@Slf4j @Service class.

Dependencies: RocksDBClient, RocksDBProperties

Public method'lar:

1. void saveResponse(String compositeId, byte[] body, int httpStatus, String contentType):
   - ResponseMetadata yarat (savedAt = System.currentTimeMillis(), contentLength = body.length)
   - rocksDBClient.putResponse(compositeId, body)
   - rocksDBClient.putResponseMetadata(compositeId, meta)
   - log.debug("Response saved to RocksDB: {}", compositeId)

2. byte[] getResponseBody(String compositeId):
   - return rocksDBClient.getResponse(compositeId)

3. ResponseMetadata getResponseMetadata(String compositeId):
   - return rocksDBClient.getResponseMetadata(compositeId)

4. @Scheduled(cron = "#{rocksDBProperties.cleanupCron}")
   void cleanupExpiredResponses():
   - long cutoffTime = System.currentTimeMillis() - (props.getResponseRetentionDays() * 86400000L)
   - rocksDBClient.iterateMetadata() orqali har bir metadata'ni tekshir
   - meta.getSavedAt() < cutoffTime bo'lsa → rocksDBClient.deleteResponse(compositeId)
   - O'chirilgan va qolgan sonini logla
   - @EnableScheduling allaqachon bor (RequestPuller'da @Scheduled ishlaydi)
```

- [ ] **Step 3: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/storage/
git commit -m "feat: add RocksDB client and response storage service with 7-day cleanup"
```

---

## Feature 4: Exception Handling va Authorization Framework

**Maqsad:** GlobalExceptionHandler, pluggable authorization, error model.

### Task 4.1: Exception class'lar va error model

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/exception/ErrorResponse.java`
- Create: `src/main/java/uz/greenwhite/gateway/exception/AuthorizationDeniedException.java`
- Create: `src/main/java/uz/greenwhite/gateway/exception/RequestValidationException.java`
- Create: `src/main/java/uz/greenwhite/gateway/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Exception va GlobalExceptionHandler yaratish**

**Prompt:**
```
qosid-flow loyihasida exception handling package yarat:

1. src/main/java/uz/greenwhite/gateway/exception/ErrorResponse.java:
   @Data @Builder @AllArgsConstructor
   - String error (masalan "Unauthorized")
   - String message (masalan "Authorization denied: invalid token")
   - int status (masalan 401)
   - LocalDateTime timestamp

2. src/main/java/uz/greenwhite/gateway/exception/AuthorizationDeniedException.java:
   extends RuntimeException
   - int httpStatus field (default 403)
   - Constructor: (String message) va (String message, int httpStatus)

3. src/main/java/uz/greenwhite/gateway/exception/RequestValidationException.java:
   extends RuntimeException
   - Constructor: (String message)

4. src/main/java/uz/greenwhite/gateway/exception/GlobalExceptionHandler.java:
   @Slf4j @RestControllerAdvice

   Exception handler method'lar:

   @ExceptionHandler(AuthorizationDeniedException.class)
   ResponseEntity<ErrorResponse> → exception.getHttpStatus() (default 403)

   @ExceptionHandler(AuthenticationException.class) — org.springframework.security.core.AuthenticationException yoki Spring'ning standart exception'i
   ResponseEntity<ErrorResponse> → 401

   @ExceptionHandler(HttpClientErrorException.class)
   ResponseEntity<ErrorResponse> → original status code

   @ExceptionHandler(RequestValidationException.class)
   ResponseEntity<ErrorResponse> → 400

   @ExceptionHandler(MaxUploadSizeExceededException.class)
   ResponseEntity<ErrorResponse> → 413

   @ExceptionHandler(Exception.class)
   ResponseEntity<ErrorResponse> → 500

   Har bir handler'da log.warn/error qil va ErrorResponse qaytar:
   ErrorResponse.builder()
     .error(HttpStatus.xxx.getReasonPhrase())
     .message(e.getMessage())
     .status(statusCode)
     .timestamp(LocalDateTime.now())
     .build()

Package: uz.greenwhite.gateway.exception
Eslatma: Spring Security dependency yo'q bo'lishi mumkin. AuthenticationException o'rniga oddiy Exception class'dan extend qilingan custom exception ishlat.
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/exception/
git commit -m "feat: add GlobalExceptionHandler with standard error response format"
```

---

### Task 4.2: Authorization framework

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/auth/AuthContext.java`
- Create: `src/main/java/uz/greenwhite/gateway/auth/AuthResult.java`
- Create: `src/main/java/uz/greenwhite/gateway/auth/AuthorizationProvider.java`
- Create: `src/main/java/uz/greenwhite/gateway/auth/NoOpAuthorizationProvider.java`

- [ ] **Step 1: Auth framework yaratish**

**Prompt:**
```
qosid-flow loyihasida pluggable authorization framework yarat:

1. src/main/java/uz/greenwhite/gateway/auth/AuthContext.java:
   @Data @Builder @AllArgsConstructor
   - String uri
   - String method (GET/POST/...)
   - Map<String, String> headers
   - String clientIp
   - String queryParams

2. src/main/java/uz/greenwhite/gateway/auth/AuthResult.java:
   @Data @AllArgsConstructor(access = AccessLevel.PRIVATE)
   - boolean allowed
   - String reason (null agar allowed)
   - int httpStatus (default 403)

   Static factory method'lar:
   - static AuthResult allowed() → new AuthResult(true, null, 200)
   - static AuthResult denied(String reason) → new AuthResult(false, reason, 403)
   - static AuthResult denied(String reason, int httpStatus) → new AuthResult(false, reason, httpStatus)

3. src/main/java/uz/greenwhite/gateway/auth/AuthorizationProvider.java:
   public interface AuthorizationProvider {
       AuthResult authorize(AuthContext context);
   }

4. src/main/java/uz/greenwhite/gateway/auth/NoOpAuthorizationProvider.java:
   @Component
   @ConditionalOnMissingBean(AuthorizationProvider.class)
   public class NoOpAuthorizationProvider implements AuthorizationProvider {
       @Override
       public AuthResult authorize(AuthContext context) {
           return AuthResult.allowed();
       }
   }

Package: uz.greenwhite.gateway.auth
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/auth/
git commit -m "feat: add pluggable authorization framework with NoOp default"
```

---

## Feature 5: REST API Layer

**Maqsad:** ProxyController, StatusController — transparent proxy va status/result endpoint'lar.

### Task 5.1: API response model'lar

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/api/model/ProxyResponse.java`
- Create: `src/main/java/uz/greenwhite/gateway/api/model/StatusResponse.java`
- Create: `src/main/java/uz/greenwhite/gateway/api/model/CallbackPayload.java`

- [ ] **Step 1: API model'lar yaratish**

**Prompt:**
```
qosid-flow loyihasida REST API uchun response model'lar yarat:

1. src/main/java/uz/greenwhite/gateway/api/model/ProxyResponse.java:
   @Data @Builder
   - String requestId (compositeId — masalan "api:uuid-here")
   - String status ("ACCEPTED")
   - String statusUrl ("/qosid/api/v1/requests/{id}/status")

2. src/main/java/uz/greenwhite/gateway/api/model/StatusResponse.java:
   @Data @Builder
   @JsonInclude(JsonInclude.Include.NON_NULL)
   - String requestId
   - String status (PROCESSING / COMPLETED / FAILED)
   - Object result (nullable — inline natija, < threshold va JSON/text)
   - String resultUrl (nullable — katta natija uchun URL)
   - String errorMessage (nullable — FAILED holat uchun)
   - String errorSource (nullable — HTTP/SYSTEM/CIRCUIT_BREAKER)

3. src/main/java/uz/greenwhite/gateway/api/model/CallbackPayload.java:
   @Data @Builder @NoArgsConstructor @AllArgsConstructor
   @JsonInclude(JsonInclude.Include.NON_NULL)
   - String requestId
   - String status (COMPLETED / FAILED)
   - int httpStatus
   - String contentType
   - Object result (nullable)
   - String resultUrl (nullable)
   - String errorMessage (nullable)

Package: uz.greenwhite.gateway.api.model
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/api/
git commit -m "feat: add API response models (ProxyResponse, StatusResponse, CallbackPayload)"
```

---

### Task 5.2: ProxyController

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/api/ProxyController.java`

- [ ] **Step 1: ProxyController yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/api/ProxyController.java yarat:

@Slf4j @RestController @RequestMapping("/qosid/queue") @RequiredArgsConstructor class.

Dependencies:
- AuthorizationProvider authorizationProvider
- RequestProducer requestProducer
- KafkaProperties kafkaProperties
- GatewayMetrics metrics (agar API metrikalari bo'lsa)

Endpoint:
@RequestMapping(value = "/**", method = {RequestMethod.POST, RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
public ResponseEntity<ProxyResponse> handleRequest(HttpServletRequest request)

Logic:
1. Authorization:
   AuthContext context = AuthContext.builder()
     .uri(request.getRequestURI())
     .method(request.getMethod())
     .headers(extractHeaders(request))
     .clientIp(getClientIp(request))
     .queryParams(request.getQueryString())
     .build();
   AuthResult authResult = authorizationProvider.authorize(context);
   if (!authResult.isAllowed()) throw new AuthorizationDeniedException(authResult.getReason(), authResult.getHttpStatus());

2. URI extraction:
   String targetUri = request.getRequestURI().replaceFirst("/qosid/queue", "");

3. compositeId generation:
   String compositeId = "api:" + UUID.randomUUID();

4. RequestMessage yaratish:
   RequestMessage message = RequestMessage.builder()
     .source("API")
     .uri(targetUri)
     .method(request.getMethod())
     .headers(extractHeaders(request))
     .body(request.getInputStream().readAllBytes()) // byte[] → new String(bytes, UTF_8) chunki hozir body String
     .params(request.getQueryString())
     .clientIp(getClientIp(request))
     .contentType(request.getContentType())
     .callbackUrl(request.getHeader("X-Callback-Url"))
     .build();

   MUHIM: RequestMessage'da companyId va requestId null bo'ladi API so'rovlar uchun.
   compositeId'ni boshqacha saqlash kerak — requestId o'rniga UUID hashCode yoki alohida field.
   Yoki: companyId=0, requestId=UUID.hashCode() qo'yish (lekin collision riski bor).
   
   Eng yaxshi yechim: RequestMessage'ga compositeId field'ni to'g'ridan-to'g'ri qo'shish.
   Hozircha: request.setCompanyId(0L); request.setRequestId(UUID hash yoki random long);
   getCompositeId() mavjud method "companyId:requestId" qaytaradi.

5. Kafka'ga yuborish:
   requestProducer.sendRequest(message).get(10, TimeUnit.SECONDS);

6. Response:
   String compositeId = message.getCompositeId();
   return ResponseEntity.status(HttpStatus.ACCEPTED).body(
     ProxyResponse.builder()
       .requestId(compositeId)
       .status("ACCEPTED")
       .statusUrl("/qosid/api/v1/requests/" + compositeId + "/status")
       .build()
   );

Helper method'lar:
- Map<String, String> extractHeaders(HttpServletRequest request) — barcha header'larni Map'ga o'tkaz
- String getClientIp(HttpServletRequest request) — X-Forwarded-For yoki X-Real-IP yoki remoteAddr

Import'lar: jakarta.servlet.http.HttpServletRequest, java.util.UUID, java.util.concurrent.TimeUnit

ESLATMA: BiruniUpload header logikasi (multipart/file upload) hozircha qo'shilmaydi — Feature 7 da qo'shiladi. Hozir faqat oddiy so'rovlar (body as-is).
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/api/ProxyController.java
git commit -m "feat: add ProxyController for transparent proxy /qosid/queue/**"
```

---

### Task 5.3: StatusController (status + result endpoint'lar)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/api/StatusController.java`

- [ ] **Step 1: StatusController yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/api/StatusController.java yarat:

@Slf4j @RestController @RequestMapping("/qosid/api/v1/requests") @RequiredArgsConstructor class.

Dependencies:
- RequestStateService requestStateService
- ResponseStorageService responseStorageService
- @Value("${gateway.response.threshold-size:1024}") int responseThresholdSize
- ObjectMapper objectMapper

Endpoint 1 — Status:
@GetMapping("/{id}/status")
public ResponseEntity<StatusResponse> getStatus(@PathVariable String id)

Logic:
1. Optional<RequestState> state = requestStateService.getState(id);
   if (state.isEmpty()) return ResponseEntity.notFound().build();

2. RequestState s = state.get();
   StatusResponse.StatusResponseBuilder builder = StatusResponse.builder()
     .requestId(id)
     .status(s.getStatus().name());

3. if (s.getStatus() == RequestStatus.COMPLETED && s.isHasResult()):
   ResponseMetadata meta = responseStorageService.getResponseMetadata(id);
   if (meta != null):
     byte[] body = responseStorageService.getResponseBody(id);
     if (body != null && body.length < responseThresholdSize):
       MediaType mediaType = MediaType.parseMediaType(meta.getContentType());
       if (mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)):
         builder.result(objectMapper.readTree(body));
       elif (mediaType.isCompatibleWith(MediaType.TEXT_PLAIN)):
         builder.result(new String(body, StandardCharsets.UTF_8));
       else:
         builder.resultUrl("/qosid/api/v1/requests/" + id + "/result");
     else:
       builder.resultUrl("/qosid/api/v1/requests/" + id + "/result");

4. if (s.getStatus() == RequestStatus.FAILED):
   builder.errorMessage(s.getLastError());
   builder.errorSource(s.getErrorSource() != null ? s.getErrorSource().name() : null);

5. return ResponseEntity.ok(builder.build());


Endpoint 2 — Result:
@GetMapping("/{id}/result")
public ResponseEntity<byte[]> getResult(@PathVariable String id)

Logic:
1. ResponseMetadata meta = responseStorageService.getResponseMetadata(id);
   if (meta == null) return ResponseEntity.notFound().build();

2. byte[] body = responseStorageService.getResponseBody(id);
   if (body == null) return ResponseEntity.notFound().build();

3. return ResponseEntity.status(meta.getHttpStatus())
     .contentType(MediaType.parseMediaType(meta.getContentType()))
     .contentLength(body.length)
     .body(body);

Import'lar: org.springframework.http.MediaType, java.nio.charset.StandardCharsets, com.fasterxml.jackson.databind.ObjectMapper
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/api/StatusController.java
git commit -m "feat: add StatusController with /status and /result endpoints"
```

---

### Task 5.4: ResponseConsumer'ni RocksDB va source routing bilan yangilash

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java`

- [ ] **Step 1: ResponseConsumer kengaytirish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java ni o'qi.

Quyidagi o'zgarishlarni qil:

1. Yangi dependency qo'sh:
   - ResponseStorageService responseStorageService
   - RequestStateService requestStateService (agar yo'q bo'lsa)

2. consumeResponse() method'ida, ack.acknowledge() dan oldin:

   a. Response body'ni RocksDB'ga saqlash (har doim — source'dan qat'i nazar):
      if (message.getBody() != null) {
          responseStorageService.saveResponse(
              message.getCompositeId(),
              message.getBody(),
              message.getHttpStatus(),
              message.getContentType()
          );
          requestStateService.updateHasResult(message.getCompositeId(), true);
      }

   b. Source-based routing:
      if ("ORACLE".equals(message.getSource())) {
          // Mavjud logika — target app'ga saqlash (saveWithRetry)
      }
      // "API" source uchun target app'ga saqlash kerak EMAS — faqat RocksDB + Redis

3. RequestStateService'ga yangi method kerak bo'ladi: updateHasResult(String compositeId, boolean hasResult)
   Bu method RequestState'dagi hasResult field'ni yangilaydi.
   src/main/java/uz/greenwhite/gateway/state/RequestStateService.java ga bu method'ni qo'sh.

MUHIM: Mavjud retry logikasini o'zgartirma! MANUAL ack'ni o'zgartirma! @ai-context/SAFETY-RULES.md ga rioya qil.
Faqat yangi code qo'sh, mavjud code'ni saqla.
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java src/main/java/uz/greenwhite/gateway/state/RequestStateService.java
git commit -m "feat: add RocksDB response storage and source-based routing in ResponseConsumer"
```

---

## Feature 6: Callback System

**Maqsad:** So'rov natijasi tayyor bo'lganda callback URL'ga HTTP POST qilish.

### Task 6.1: CallbackSender va CallbackService

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/callback/CallbackSender.java`
- Create: `src/main/java/uz/greenwhite/gateway/callback/CallbackService.java`

- [ ] **Step 1: Callback komponentlarini yaratish**

**Prompt:**
```
qosid-flow loyihasida callback tizimini yarat:

1. src/main/java/uz/greenwhite/gateway/callback/CallbackSender.java:
   @Slf4j @Service @RequiredArgsConstructor class.

   Dependency: WebClient (yoki yangi RestClient yarat)
   
   @Retryable(retryFor = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
   public void sendCallback(String callbackUrl, CallbackPayload payload) {
       log.info("Sending callback to {}: requestId={}", callbackUrl, payload.getRequestId());
       // WebClient yoki RestClient bilan POST qil
       // Response 2xx bo'lmasa exception throw qil (retry uchun)
   }

   @Recover
   public void recoverCallback(Exception e, String callbackUrl, CallbackPayload payload) {
       log.error("Callback failed after 3 attempts: url={}, requestId={}, error={}", 
                 callbackUrl, payload.getRequestId(), e.getMessage());
   }

2. src/main/java/uz/greenwhite/gateway/callback/CallbackService.java:
   @Slf4j @Service @RequiredArgsConstructor class.

   Dependencies:
   - CallbackSender callbackSender
   - ResponseStorageService responseStorageService
   - @Value("${gateway.response.threshold-size:1024}") int thresholdSize

   public void sendCallbackIfNeeded(ResponseMessage response) {
       if (response.getCallbackUrl() == null || response.getCallbackUrl().isBlank()) return;

       CallbackPayload.CallbackPayloadBuilder builder = CallbackPayload.builder()
           .requestId(response.getCompositeId())
           .status(response.isSuccess() ? "COMPLETED" : "FAILED")
           .httpStatus(response.getHttpStatus())
           .contentType(response.getContentType());

       if (!response.isSuccess()) {
           builder.errorMessage(response.getErrorMessage());
       }

       if (response.getBody() != null && response.getBody().length < thresholdSize) {
           // Inline result (try JSON parse, fallback to string)
           try {
               builder.result(new ObjectMapper().readTree(response.getBody()));
           } catch (Exception e) {
               builder.result(new String(response.getBody(), StandardCharsets.UTF_8));
           }
       } else if (response.getBody() != null) {
           builder.resultUrl("/qosid/api/v1/requests/" + response.getCompositeId() + "/result");
       }

       callbackSender.sendCallback(response.getCallbackUrl(), builder.build());
   }

@EnableRetry annotatsiyasini GatewayApplication.java yoki alohida config class'ga qo'sh.
```

- [ ] **Step 2: ResponseConsumer'ga callback trigger qo'shish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java ni o'qi.

CallbackService dependency'sini qo'sh.
consumeResponse() method'ida, RocksDB saqlashdan keyin:

    // Callback trigger
    callbackService.sendCallbackIfNeeded(message);

Shu bitta qator qo'sh, boshqa hech narsani o'zgartirma.
```

- [ ] **Step 3: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/callback/ src/main/java/uz/greenwhite/gateway/kafka/consumer/ResponseConsumer.java
git commit -m "feat: add callback system with retry and ResponseConsumer integration"
```

---

### Task 6.2: CallbackController

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/api/CallbackController.java`

- [ ] **Step 1: CallbackController yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/api/CallbackController.java yarat:

@Slf4j @RestController @RequestMapping("/qosid/api/v1/callback") @RequiredArgsConstructor class.

Endpoint:
@PostMapping
public ResponseEntity<Void> handleCallback(@RequestBody CallbackPayload payload) {
    log.info("Received callback: requestId={}, status={}", payload.getRequestId(), payload.getStatus());
    
    // Hozircha faqat log qilish va 200 qaytarish
    // Kelajakda: callback payload'ni qayta ishlash logikasi
    
    return ResponseEntity.ok().build();
}

Package: uz.greenwhite.gateway.api
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/api/CallbackController.java
git commit -m "feat: add CallbackController for receiving external callbacks"
```

---

## Feature 7: Content-Type Validation va File Upload

**Maqsad:** MIME type whitelist, MinIO + Cloudflare R2, multipart handling.

### Task 7.1: Content-Type Validator

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/file/ContentTypeValidator.java`

- [ ] **Step 1: ContentTypeValidator yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/file/ContentTypeValidator.java yarat:

@Slf4j @Component class.

Dependencies: @Value("${gateway.security.content-types}") List<String> allowedContentTypes

Method'lar:

public boolean isAllowed(String contentType) {
    if (contentType == null) return false;
    // MediaType parse qilib, type/subtype bo'yicha tekshir
    // "image/jpeg; charset=utf-8" kabi qiymatlar uchun faqat "image/jpeg" ni tekshir
    String normalized = contentType.split(";")[0].trim().toLowerCase();
    return allowedContentTypes.contains(normalized);
}

public void validate(String contentType) {
    if (!isAllowed(contentType)) {
        throw new RequestValidationException("Unsupported content type: " + contentType);
    }
}

Package: uz.greenwhite.gateway.file
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/file/ContentTypeValidator.java
git commit -m "feat: add ContentTypeValidator with MIME whitelist"
```

---

### Task 7.2: S3 Clients (MinIO + Cloudflare R2)

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/file/FileProperties.java`
- Create: `src/main/java/uz/greenwhite/gateway/file/s3/MinIOClient.java`
- Create: `src/main/java/uz/greenwhite/gateway/file/s3/CloudflareR2Client.java`

- [ ] **Step 1: FileProperties yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/file/FileProperties.java yarat:

@Getter @Setter @Component @ConfigurationProperties(prefix = "gateway.s3")
public class FileProperties {
    private MinioConfig minio = new MinioConfig();
    private R2Config cloudflareR2 = new R2Config();

    @Getter @Setter
    public static class MinioConfig {
        private boolean enabled = false;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket = "gateway-files";
    }

    @Getter @Setter
    public static class R2Config {
        private boolean enabled = false;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String tempBucket;
        private String permanentBucket;
        private int linkExpirationHours = 24;
    }
}
```

- [ ] **Step 2: MinIOClient yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/file/s3/MinIOClient.java yarat:

@Slf4j @Component @ConditionalOnProperty(name = "gateway.s3.minio.enabled", havingValue = "true")
class.

Old loyihadagi MinIOClient'ni reference sifatida ishlatib yarat:
Fayl: old/messagebroker/src/main/java/uz/greenwhite/messagebroker/s3/MinIOClient.java

Asosiy funksionallar:
- Constructor'da io.minio.MinioClient yaratish (endpoint, credentials)
- uploadObject(InputStream stream, String sha, String contentType, long fileSize)
  - checkObjectExist(sha) bilan dedup
  - Agar mavjud bo'lmasa — PutObjectArgs bilan yuklash
- checkObjectExist(String sha) → boolean (StatObjectArgs bilan tekshirish)

SHA ni object key sifatida ishlatish (old'dagi kabi).
5 daqiqa timeout.
Exception'larni log qilib RuntimeException throw qil.
```

- [ ] **Step 3: CloudflareR2Client yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/file/s3/CloudflareR2Client.java yarat:

@Slf4j @Component @ConditionalOnProperty(name = "gateway.s3.cloudflare-r2.enabled", havingValue = "true")
class.

Old loyihadagi CloudflareR2Client'ni reference sifatida ishlatib yarat:
Fayl: old/messagebroker/src/main/java/uz/greenwhite/messagebroker/s3/CloudflareR2Client.java

Asosiy funksionallar:
- Constructor'da software.amazon.awssdk.services.s3.S3Client yaratish
  - pathStyleAccessEnabled = true
  - Region: "auto"
- uploadObject(String bucket, String sha, InputStream stream, String contentType, long fileSize)
  - PutObjectRequest bilan yuklash
- getObjectPath(String sha) → "XX/YY/full_sha" (2-char sharding)
- TEMP va PERMANENT bucket support
- Link generation (presigned URL, linkExpirationHours)

FileProperties.R2Config dan config olish.
```

- [ ] **Step 4: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/file/
git commit -m "feat: add MinIO and Cloudflare R2 S3 clients with SHA dedup"
```

---

### Task 7.3: FileService va ServletFileUploadEntity

**Files:**
- Create: `src/main/java/uz/greenwhite/gateway/file/FileService.java`
- Create: `src/main/java/uz/greenwhite/gateway/file/ServletFileUploadEntity.java`

- [ ] **Step 1: FileService yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/file/FileService.java yarat:

@Slf4j @Service @RequiredArgsConstructor class.

Dependencies:
- Optional<MinIOClient> minIOClient (S3 disabled bo'lsa inject qilinmaydi)
- Optional<CloudflareR2Client> r2Client
- ContentTypeValidator contentTypeValidator

Method'lar:

public List<String> uploadFiles(List<ServletFileUploadEntity.FileData> files) {
    List<String> shaList = new ArrayList<>();
    for (var file : files) {
        contentTypeValidator.validate(file.getContentType());
        String sha = uploadFile(file);
        shaList.add(sha);
    }
    return shaList;
}

private String uploadFile(ServletFileUploadEntity.FileData file) {
    if (file.getBucket() == null) {
        // MinIO
        minIOClient.orElseThrow(() -> new IllegalStateException("MinIO is not configured"))
            .uploadObject(file.getInputStream(), file.getSha(), file.getContentType(), file.getSize());
    } else {
        // Cloudflare R2
        r2Client.orElseThrow(() -> new IllegalStateException("Cloudflare R2 is not configured"))
            .uploadObject(file.getBucket(), file.getSha(), file.getInputStream(), file.getContentType(), file.getSize());
    }
    return file.getSha();
}
```

- [ ] **Step 2: ServletFileUploadEntity yaratish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/file/ServletFileUploadEntity.java yarat.

Old loyihadagi faylni reference sifatida ishlatib yarat:
Fayl: old/messagebroker/src/main/java/uz/greenwhite/messagebroker/file/ServletFileUploadEntity.java

Asosiy funksionallar:
- HttpServletRequest'dan multipart parts'ni parse qilish
- Har bir fayl uchun SHA-256 hisoblash
- "param" mode: JSON body'dagi \u0000<index> placeholder'larni SHA bilan almashtirish
- "alone" mode: yakka fayl parsing
- FileData inner class: sha, inputStream, contentType, size, bucket

java.security.MessageDigest SHA-256 ishlatish.
```

- [ ] **Step 3: ProxyController'ga BiruniUpload mode qo'shish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/api/ProxyController.java ni o'qi.

handleRequest() method'ida, RequestMessage yaratishdan oldin BiruniUpload header tekshiruvini qo'sh:

String biruniUpload = request.getHeader("BiruniUpload");
List<String> fileReferences = null;
String bodyStr;

if ("param".equals(biruniUpload)) {
    ServletFileUploadEntity entity = new ServletFileUploadEntity(request, "param");
    fileReferences = fileService.uploadFiles(entity.getFiles());
    bodyStr = entity.getUpdatedParam(); // SHA almashtirilgan JSON
} else if ("alone".equals(biruniUpload)) {
    ServletFileUploadEntity entity = new ServletFileUploadEntity(request, "alone");
    fileReferences = fileService.uploadFiles(entity.getFiles());
    bodyStr = null; // body yo'q, faqat fayl
} else if (biruniUpload != null) {
    throw new RequestValidationException("Invalid value for BiruniUpload header: " + biruniUpload);
} else {
    bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
}

message.setBody(bodyStr);
message.setFileReferences(fileReferences);

FileService dependency'sini inject qil.
```

- [ ] **Step 4: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/file/ src/main/java/uz/greenwhite/gateway/api/ProxyController.java
git commit -m "feat: add file upload system with MinIO/R2, multipart parsing, BiruniUpload modes"
```

---

## Feature 8: HttpRequestService kengaytirish

**Maqsad:** byte[] body support, content-type validation, multipart forward.

### Task 8.1: HttpRequestService o'zgartirishlar

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java`

- [ ] **Step 1: byte[] body va content-type qo'shish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java ni o'qi.

Quyidagi o'zgarishlarni qil:

1. sendRequest() method'ida body yuborishda:
   Hozir: .bodyValue(request.getBody()) — bu String yuboradi
   Yangi: body byte[] bo'lishi mumkin. Agar request.getBody() String bo'lsa, uni bytes ga convert qil.
   
   WebClient so'rovida body yuborishda:
   - Agar contentType multipart bo'lsa va fileReferences bor bo'lsa — hozircha oddiy body yuborish (multipart forward keyingi iteratsiyada)
   - Aks holda: .bodyValue(request.getBody() != null ? request.getBody().getBytes(StandardCharsets.UTF_8) : new byte[0])
   
   Yoki agar body allaqachon byte[] bo'lsa to'g'ridan-to'g'ri yuborish.

2. buildSuccessResponse() method'ida:
   Response body'ni byte[] sifatida olish:
   .bodyToMono(byte[].class) — agar hozir String.class bo'lsa, byte[].class ga o'zgartir

3. callbackUrl propagation:
   ResponseMessage build qilayotganda request'dan callbackUrl va source field'larni ko'chir:
   .callbackUrl(request.getCallbackUrl())
   .source(request.getSource())

4. Content-type header handling:
   Agar request.getContentType() bor bo'lsa, uni ishlatish:
   httpHeaders.setContentType(MediaType.parseMediaType(request.getContentType()))
   Agar yo'q bo'lsa, default APPLICATION_JSON qoladi.

MUHIM: Circuit Breaker logikasini o'zgartirma! OAuth2 logikasini o'zgartirma! 
@ai-context/SAFETY-RULES.md ga rioya qil.
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java
git commit -m "feat: support byte[] body, content-type propagation in HttpRequestService"
```

---

## Feature 9: Metrics yangilash

**Maqsad:** API layer, RocksDB, callback metrikalari qo'shish.

### Task 9.1: GatewayMetrics yangilash

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java`

- [ ] **Step 1: Yangi metrikalar qo'shish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java ni o'qi.

Constructor'da yangi metrikalar qo'sh:

// API Layer
Counter apiRequestReceived = Counter.builder("gateway.api.request.received").register(registry);
Counter apiRequestSuccess = Counter.builder("gateway.api.request.total").tag("result", "success").register(registry);
Counter apiRequestError = Counter.builder("gateway.api.request.total").tag("result", "error").register(registry);
Timer apiRequestTimer = Timer.builder("gateway.api.request.duration").register(registry);

// RocksDB
Counter rocksdbSaveSuccess = Counter.builder("gateway.rocksdb.save.total").tag("result", "success").register(registry);
Counter rocksdbSaveError = Counter.builder("gateway.rocksdb.save.total").tag("result", "error").register(registry);

// Callback
Counter callbackSent = Counter.builder("gateway.callback.sent").register(registry);
Counter callbackFailed = Counter.builder("gateway.callback.failed").register(registry);

// File Upload
Counter fileUploadSuccess = Counter.builder("gateway.file.upload.total").tag("result", "success").register(registry);
Counter fileUploadError = Counter.builder("gateway.file.upload.total").tag("result", "error").register(registry);

@Getter annotation bilan expose qil. Mavjud metrikalarni o'zgartirma.
```

- [ ] **Step 2: Build va commit**

Run: `mvn clean compile -DskipTests`

```bash
git add src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java
git commit -m "feat: add API, RocksDB, callback, file upload metrics"
```

---

## Feature 10: Integration Test va Final Verification

**Maqsad:** TestController yangilash, to'liq build tekshirish.

### Task 10.1: TestController'ga yangi endpoint'lar

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/controller/TestController.java`

- [ ] **Step 1: Test endpoint'lar qo'shish**

**Prompt:**
```
src/main/java/uz/greenwhite/gateway/controller/TestController.java ni o'qi.

Yangi test endpoint'lar qo'sh (@Profile("dev") ichida):

1. POST /api/test/proxy — ProxyController'ni test qilish uchun:
   Fake request yaratib Kafka'ga yuboradi (source="API", compositeId="api:test-uuid")

2. GET /api/test/rocksdb/{id} — RocksDB'da response bormi tekshirish:
   ResponseStorageService.getResponseMetadata(id) chaqiradi va qaytaradi

3. POST /api/test/callback — Callback test:
   CallbackSender'ga test payload yuboradi

Yangi dependency'larni inject qil: ResponseStorageService, CallbackSender (agar kerak bo'lsa).
```

- [ ] **Step 2: To'liq build**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/uz/greenwhite/gateway/controller/TestController.java
git commit -m "feat: add test endpoints for proxy, RocksDB, callback"
```

---

### Task 10.2: Final verification va cleanup

- [ ] **Step 1: To'liq compile va package**

Run: `mvn clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: Application start test**

Run: `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
Expected: Application starts, /actuator/health returns UP

- [ ] **Step 3: AI context docs yangilash**

**Prompt:**
```
@ai-context/ papkadagi hujjatlarni yangi funksionallar bilan yangilaish kerak:

1. @ai-context/OVERVIEW.md — yangi component'lar: API Layer, RocksDB, S3, Callback
2. @ai-context/ARCHITECTURE.md — yangi pipeline diagramma (REST API entry point)
3. @ai-context/CONFIGURATION.md — yangi config parametrlari
4. @ai-context/EXTENSION-GUIDE.md — AuthorizationProvider qo'shish yo'riqnomasi
5. @ai-context/SAFETY-RULES.md — yangi safety rules (RocksDB cleanup, callback retry)
6. CLAUDE.md — yangi endpoint'lar va config

Har bir faylni o'qi, yangi ma'lumotlarni qo'sh, mavjudlarni o'zgartirma.
```

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "docs: update AI context documentation with new features"
```

---

## Execution Order Summary

```
Feature 1: Dependencies + Server Tuning + Config    [Foundation]
    ↓
Feature 2: Model Changes (RequestMessage, ResponseMessage, RequestState)  [Data Layer]
    ↓
Feature 3: RocksDB Response Storage                  [Storage Layer]
    ↓
Feature 4: Exception Handling + Authorization        [Cross-cutting]
    ↓
Feature 5: REST API Layer (Proxy + Status/Result)    [API Layer]
    ↓
Feature 6: Callback System                           [Cross-cutting]
    ↓
Feature 7: Content-Type + File Upload (S3)           [Cross-cutting]
    ↓
Feature 8: HttpRequestService enhancements           [Core Pipeline]
    ↓
Feature 9: Metrics                                   [Observability]
    ↓
Feature 10: Testing + Verification + Docs            [Final]
```

Har bir Feature mustaqil commit'lar bilan yakunlanadi. Feature'lar ketma-ket bajarilishi kerak — har biri oldingisiga bog'liq.
