# Qosid-Flow Feature Parity & Enhancement Design

**Date:** 2026-04-10
**Status:** Approved
**Scope:** Old messagebroker'dagi yetishmayotgan funksionallarni qosid-flow'ga qo'shish + texnik yaxshilashlar

---

## 1. Kontekst

Qosid-flow — avtonom message gateway. Hech bir backend'ga bog'liq emas. Bitta qosid-flow instance bitta main app uchun ishlaydi. Main app'ning HAProxy'si `/qosid/` prefix'li so'rovlarni qosid-flow'ga yo'naltiradi. Qosid-flow so'rovni config'dagi `target-base-url` ga yuboradi (ya'ni o'sha main app'ga qaytaradi), lekin load balancing, retry, circuit breaker bilan.

**Kelajakdagi arxitektura:**
```
Client -> Proxy Server -> Main App (HAProxy) -> qosid-flow -> Main App (target)
            |                                       |
            +--- connection ochiq ------- poll status/result ---+
```
Proxy server hozircha qurilmaydi, lekin qosid-flow unga tayyor bo'lishi kerak.

### Asosiy tamoyillar

- Qosid-flow avtonom — hech bir backend'ning ichki API'siga bog'liq emas
- Barcha feature'lar pluggable — kerak bo'lmasa o'chirib qo'yish mumkin
- Client info (IP, headers) har bir qatlamda saqlanadi (statistika uchun)
- Mavjud E1-E5 pipeline buzilmaydi — yangi funksionallar layer sifatida qo'shiladi

---

## 2. Arxitektura — Modular (B yondashuv)

```
+-------------------------------------------------------+
|  API Layer (yangi)                                     |
|  +-- ProxyController (/qosid/queue/**)                 |
|  +-- StatusController (/qosid/api/v1/requests/*)       |
|  +-- CallbackController                                |
|  +-- AuthorizationProvider (pluggable)                  |
+-------------------------------------------------------+
|  Core Pipeline (mavjud, kengaytirilgan)                |
|  +-- E1: RequestPuller (Oracle) — o'zgarmaydi          |
|  +-- E2: RequestProducer — o'zgarmaydi                 |
|  +-- E3: RequestConsumer — kichik o'zgarish            |
|  +-- E4: HttpRequestService — kengaytiriladi           |
|  +-- E5: ResponseConsumer — kengaytiriladi             |
+-------------------------------------------------------+
|  Storage Layer (yangi + mavjud)                        |
|  +-- Redis (state, lock, token) — mavjud               |
|  +-- RocksDB (response body) — yangi                   |
|  +-- S3 (file upload) — yangi                          |
+-------------------------------------------------------+
|  Cross-cutting (yangi + kengaytirilgan)                |
|  +-- FileService (MinIO + R2)                          |
|  +-- CallbackService                                   |
|  +-- ContentTypeValidator                              |
|  +-- GlobalExceptionHandler                            |
+-------------------------------------------------------+
```

---

## 3. API Layer

### 3.1 Endpoint'lar

| Method | Path | Maqsad |
|--------|------|--------|
| POST/GET | `/qosid/queue/**` | Transparent proxy — so'rov qabul qilish |
| GET | `/qosid/api/v1/requests/{id}/status` | Holat tekshirish |
| GET | `/qosid/api/v1/requests/{id}/result` | Natija olish (RocksDB'dan) |
| POST | `/qosid/api/v1/callback` | Tashqi callback qabul qilish |

### 3.2 Proxy Flow (/qosid/queue/**)

1. `AuthorizationProvider.authorize(context)` — pluggable auth tekshirish
2. Path'dan `/qosid/queue` olib tashlanadi -> target URI hosil bo'ladi
3. `BiruniUpload` header tekshiriladi:
   - `null` -> oddiy so'rov (body as-is)
   - `"param"` -> multipart: fayllar S3'ga, JSON'dagi `\u0000<index>` placeholder'lar SHA bilan almashtiriladi
   - `"alone"` -> yakka fayl: S3'ga yuklanadi
4. `RequestMessage` yaratiladi (source="API", target URI, method, headers, body/SHA, client info)
5. Kafka'ga yuboriladi (`gateway.request.new`)
6. Javob: `202 ACCEPTED` + requestId + statusUrl

**compositeId generatsiya (API so'rovlar uchun):**
- Oracle so'rovlar: `companyId:requestId` (Oracle'dan keladi)
- API so'rovlar: UUID generatsiya qilinadi (masalan: `api:550e8400-e29b-41d4-a716-446655440000`)
- Prefix `api:` orqali source farqlanadi

**Response format:**
```json
{
  "requestId": "api:550e8400-e29b-41d4-a716-446655440000",
  "status": "ACCEPTED",
  "statusUrl": "/qosid/api/v1/requests/api:550e8400-e29b-41d4-a716-446655440000/status"
}
```

### 3.3 Status Endpoint

```
GET /qosid/api/v1/requests/{id}/status
```

| Holat | Response |
|-------|----------|
| PROCESSING | `{ "status": "PROCESSING", "requestId": "..." }` |
| COMPLETED, < 1KB, JSON/text | `{ "status": "COMPLETED", "result": {...} }` |
| COMPLETED, >= 1KB yoki binary | `{ "status": "COMPLETED", "resultUrl": "/qosid/api/v1/requests/.../result" }` |
| FAILED | `{ "status": "FAILED", "errorMessage": "...", "errorSource": "HTTP" }` |

**Response threshold:** 1024 byte (configurable). < threshold va JSON/text bo'lsa inline, aks holda URL.

### 3.4 Result Endpoint

```
GET /qosid/api/v1/requests/{id}/result
```

- RocksDB'dan `byte[]` o'qiladi
- Original Content-Type, Content-Length header bilan qaytariladi
- Binary-safe (PDF, image, JSON — hammasi to'g'ri)
- Natija topilmasa yoki muddati o'tgan bo'lsa -> 404

### 3.5 Authorization — Pluggable Interface

```java
public interface AuthorizationProvider {
    AuthResult authorize(AuthContext context);
}

// AuthContext field'lari: uri, method, headers, clientIp, queryParams
// AuthResult: allowed() | denied(reason, httpStatus)
```

- Default: `NoOpAuthorizationProvider` (`@ConditionalOnMissingBean`) — hamma so'rov o'tadi
- Foydalanuvchi `@Component` sifatida o'z implementatsiyasini qo'shadi -> Spring avtomatik topadi
- Build vaqtida compile bo'ladi — type-safe, IDE support

### 3.6 Client Info saqlash

Target app'ga yuborilayotgan so'rovda saqlanadigan headerlar:
- `X-Forwarded-For`, `X-Real-IP` — client IP
- `User-Agent` — client identifikatsiyasi
- `X-Forwarded-Host`, `Host` — original host
- Barcha original headerlar saqlanadi va target app'ga yuboriladi

---

## 4. Core Pipeline kengaytirish

### 4.1 RequestMessage — yangi field'lar

```java
// Mavjud field'lar (o'zgarmaydi)
companyId, requestId, filialId, endpointId, baseUrl, uri, params,
method, headers, body, oauth2Provider, callbackProcedure,
projectCode, createdAt

// Yangi field'lar
source          // "ORACLE" | "API" — so'rov manbasi
callbackUrl     // X-Callback-Url header'dan (nullable)
fileReferences  // S3'ga yuklangan fayllar SHA ro'yxati (nullable)
clientIp        // Statistika uchun
contentType     // Original content-type
```

### 4.2 ResponseMessage — o'zgarishlar

```java
// O'zgarish
body            // String -> byte[] (binary-safe)

// Yangi field'lar
callbackUrl     // RequestMessage'dan ko'chiriladi
rocksDbKey      // RocksDB'da response body key
source          // "ORACLE" | "API"
```

### 4.3 Pipeline bosqichlari

**E1 (RequestPuller)** — o'zgarmaydi. `source = "ORACLE"` qo'yadi.

**E2 (RequestProducer)** — o'zgarmaydi. REST API ham xuddi shu producer'ni ishlatadi.

**E3 (RequestConsumer)** — kichik o'zgarish:
- `byte[]` body support (multipart so'rovlar uchun)
- Qolgan logika (idempotency, lock, async delegate) o'zgarmaydi

**E4 (HttpRequestService)** — kengaytiriladi:
- `byte[]` body support (hozir faqat String)
- Multipart forward: `fileReferences` bor bo'lsa, S3'dan fayllarni olib multipart tuzib yuborish
- Content-type validation (whitelist asosida)
- WebClient connection pool aniq sozlash

**E5 (ResponseConsumer)** — kengaytiriladi:
- Response body'ni RocksDB'ga saqlash (`byte[]`, 7 kun)
- `source = "ORACLE"` -> target app'ga ham saqlash (mavjud logika)
- `source = "API"` -> faqat RocksDB + Redis status
- `callbackUrl` bor bo'lsa -> CallbackService'ga delegate

### 4.4 Pipeline diagramma

```
REST API --> [Auth] --> [File Upload?] --+
                                        +--> E2: Kafka --> E3: Consumer --> E4: HTTP + CB
Oracle Pull ----------------------------+                                       |
                                                                          E5: ResponseConsumer
                                                                         /      |         \
                                                                    RocksDB   Oracle*   Callback*
                                                                    (7 kun)  (source=   (agar URL
                                                                             ORACLE)    berilsa)
```

---

## 5. Storage Layer

### 5.1 RocksDB — Response Body Storage

**Key structure:**
```
"response:{compositeId}"       ->  byte[] (raw response body)
"response_meta:{compositeId}"  ->  ResponseMetadata {status, contentType, contentLength, savedAt}
```

**Operatsiyalar:**
- `putResponse(compositeId, byte[] body, ResponseMetadata meta)`
- `getResponse(compositeId) -> byte[]`
- `getResponseMetadata(compositeId) -> ResponseMetadata`
- `deleteResponse(compositeId)`

**Retention:** 7 kun. Cron job har kuni 00:00:
- `response_meta:*` iterate
- `savedAt + 7 kun < now` -> body + meta o'chiriladi

**Config:**
```yaml
gateway:
  rocksdb:
    path: ${ROCKSDB_PATH:./data/rocksdb}
    response-retention-days: ${ROCKSDB_RETENTION_DAYS:7}
    cleanup-cron: "0 0 0 * * *"
```

### 5.2 Redis — Mavjud + kichik qo'shimcha

Mavjud funksional o'zgarmaydi:
- `request:state:{compositeId}` — RequestState (72 soat TTL)
- `request:lock:{compositeId}` — Distributed lock (300s TTL)
- `oauth2:token:{provider}` — Token cache
- `oauth2:lock:{provider}` — Token refresh lock

RequestState modeliga qo'shimcha field'lar:
```java
source       // "ORACLE" | "API"
callbackUrl  // nullable
hasResult    // boolean — RocksDB'da natija bormi
```

### 5.3 S3 — Fayl yuklash

**MinIO:**
- Default storage (bucket ko'rsatilmaganda)
- SHA-256 dedup — `checkObjectExist(sha)` bilan
- Object key = SHA hash
- 5 daqiqa timeout

**Cloudflare R2:**
- Explicit bucket bilan (TEMP/PERMANENT)
- Path sharding: `XX/YY/full_sha`
- Temp bucket: 24 soat link expiry
- URI whitelist orqali bucket aniqlash

**Config:**
```yaml
gateway:
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
```

Test rejimida: `enabled: false` — fayl yuklash o'tkazib yuboriladi (log warning).

---

## 6. Cross-cutting Concerns

### 6.1 File Upload Service

**3 mode (BiruniUpload header asosida):**

| Mode | Header | Ishlashi |
|------|--------|----------|
| Oddiy | `null` | body as-is Kafka'ga |
| Param | `"param"` | Multipart parse -> fayllar S3'ga -> JSON placeholder'lar SHA bilan almashtiriladi |
| Alone | `"alone"` | Yakka fayl S3'ga -> SHA Kafka'ga |

**SHA dedup:** Upload oldidan `checkObjectExist(sha)` — bor bo'lsa o'tkazib yuboriladi.

**Multipart config:**
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 52MB
      file-size-threshold: 128KB
      resolve-lazily: true
```

### 6.2 Callback Service

**Flow:**
1. E5 da natija tayyor + `callbackUrl` bor -> `CallbackSender.sendCallback()` chaqiriladi
2. 3 urinish, 1 sek interval (`@Retryable`)

**Callback payload:**
```json
{
  "requestId": "100:12345",
  "status": "COMPLETED",
  "httpStatus": 200,
  "contentType": "application/json",
  "result": {},
  "resultUrl": "/qosid/api/v1/requests/100:12345/result",
  "errorMessage": null
}
```

- `result` < threshold bo'lsa inline, >= threshold bo'lsa `resultUrl` beriladi
- Callback Controller: tashqi tizimdan callback qabul qilish uchun

### 6.3 Content-Type Validation

Whitelist asosida fayl content-type tekshirish:
```yaml
gateway:
  security:
    content-types:
      - application/json
      - application/x-www-form-urlencoded
      - text/plain
      - multipart/form-data
      - image/jpeg, image/png, image/gif, image/webp, image/svg+xml, image/tiff, image/bmp, image/x-icon, image/heic, image/heif
      - audio/mpeg, audio/wav, audio/ogg, audio/midi, audio/x-m4a, audio/webm, audio/aac
      - video/mp4, video/mpeg, video/webm, video/quicktime, video/x-msvideo, video/x-matroska, video/3gpp
```

Whitelist'da bo'lmagan content-type -> 400 Bad Request.

### 6.4 GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    AuthorizationDeniedException     -> 403 FORBIDDEN
    AuthenticationException          -> 401 UNAUTHORIZED
    HttpClientErrorException         -> original status code
    RequestValidationException       -> 400 BAD_REQUEST
    MaxUploadSizeExceededException   -> 413 PAYLOAD_TOO_LARGE
    Exception                        -> 500 INTERNAL_SERVER_ERROR
}
```

**Response format:**
```json
{
  "error": "Unauthorized",
  "message": "Authorization denied: invalid token",
  "status": 401,
  "timestamp": "2026-04-10T12:00:00"
}
```

---

## 7. Texnik yaxshilashlar

### 7.1 Server Tuning (old metrikalaridan)

```yaml
server:
  port: ${SERVER_PORT:8090}
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
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### 7.2 Thread Pool — Configurable

```yaml
gateway:
  concurrency:
    min-concurrency: ${MIN_CONCURRENCY:10}
    max-concurrency: ${MAX_CONCURRENCY:15}
    thread-pool-multiplier: ${THREAD_POOL_MULTIPLIER:2}
    thread-pool-queue-capacity: ${THREAD_POOL_QUEUE:50}
    # max-pool = max-concurrency * multiplier = 30 (default)
```

### 7.3 WebClient Connection Pool

```yaml
gateway:
  http:
    connect-timeout-ms: ${HTTP_CONNECT_TIMEOUT:10000}
    read-timeout-ms: ${HTTP_READ_TIMEOUT:30000}
    write-timeout-ms: ${HTTP_WRITE_TIMEOUT:30000}
    max-connections: ${HTTP_MAX_CONNECTIONS:500}
    max-idle-time-ms: ${HTTP_MAX_IDLE_TIME:60000}
    max-life-time-ms: ${HTTP_MAX_LIFE_TIME:300000}
    pending-acquire-timeout-ms: ${HTTP_PENDING_ACQUIRE:45000}
```

### 7.4 Response Body — byte[] ga o'tkazish

ResponseMessage.body `String` -> `byte[]`. Bu binary content (PDF, image, audio) uchun xavfsiz.

### 7.5 Response Threshold

```yaml
gateway:
  response:
    threshold-size: ${RESPONSE_THRESHOLD_SIZE:1024}  # bytes
```

- < threshold va JSON/text -> status javobida inline
- >= threshold yoki binary -> resultUrl beriladi

---

## 8. Istisno qilingan funksionallar

| Funksional | Sabab |
|-----------|-------|
| Integration/Order Pipeline | Callback tizimi yetarli |
| Sync/Batch Request | Kafka parallelism yetarli |
| Request Resume (istalgan nuqtadan) | DLQ + monitoring yetarli |
| Message Completion Time Prediction | Kafka lag monitoring o'rnini bosadi |
| Spring Cloud Vault | Environment variables yetarli |
| Proxy Server | Kelajakda alohida quriladi |

---

## 9. Yangi dependency'lar

| Dependency | Maqsad |
|-----------|--------|
| `org.rocksdb:rocksdbjni` | Response body storage |
| `io.minio:minio` | MinIO S3 client |
| `software.amazon.awssdk:s3` | Cloudflare R2 client |
| `spring-retry` | Callback retry |

---

## 10. HAProxy config namunasi

```
frontend sx_front
   ...
   use_backend qosid_back if { path /qosid } || { path_beg /qosid/ }

backend qosid_back
   mode http
   option forwardfor
   server qosid 10.0.0.7:8090 check
```

---

## 11. Qosid-flow config namunasi (yangi field'lar)

```yaml
gateway:
  target:
    base-url: ${TARGET_BASE_URL:https://smartup.online/b}
  proxy:
    prefix: /qosid/queue
    context-path: /qosid
```

URI transformation: `/qosid/queue/trade/txs/tdeal/order$import` -> `https://smartup.online/b/trade/txs/tdeal/order$import`
