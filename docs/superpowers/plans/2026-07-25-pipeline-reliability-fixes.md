# Pipeline Reliability CRITICAL Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-07-24 auditida topilgan 4 ta kritik ishonchlilik nuqsonini tuzatish — o'lik xabar-muddati to'ri (cheksiz CB qayta-navbat halqasi), tartibsiz Kafka offset commit'i, qulf raqobatida xabar tashlanishi, va yuk ostida masshtablash rebalance'i.

**Architecture:** Hech qanday dizayn o'zgarmaydi — barcha to'rtta tuzatish **mavjud, hujjatlashtirilgan xatti-harakatni ishlatib yuborish**dan iborat. SAFETY-RULES 23-qoidasi CB qayta-navbatini (attemptCount oshirmasdan) ATAYLAB to'g'ri deb belgilaydi va `max-message-age-ms`ni "xavfsizlik to'ri" deb ataydi — biz o'sha to'rni tiriltiramiz, qayta-navbat dizaynini emas. Qo'shimcha ravishda 5 daqiqalik muddat ichidagi yozuv-kuchaytirishini to'xtatish uchun Redis'da qayta-navbat hisoblagichi qo'shiladi (`markTokenEvicted` patternining nusxasi, Redis xatosida fail-closed).

**Tech Stack:** Java 21, Spring Boot 3.3.0, Spring Kafka, Redis (Lettuce), JUnit 5 + Mockito + AssertJ, Maven reactor (`settings-core` / `app` / `ops`).

## Global Constraints

- **Maven PATH'da YO'Q.** Har bir buyruqda to'liq yo'ldan foydalaning:
  `"C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd"`
  Reja bo'ylab bu `$MVN` deb yoziladi. Reaktor ildizidan ishga tushiriladi.
- **Docker infra YOQIQ bo'lishi shart** (`docker compose up -d`) — `app` moduli testlari Redis'ni talab qiladi; Redis o'chiq bo'lsa `SecurityIsolationTest` 404 o'rniga 500 oladi va build yiqiladi.
- **SAFETY-RULES.md o'qilgan bo'lishi shart.** Tegilayotgan barcha fayllar Protected Zone'da. Har bir taskdan keyin `ai-context/SAFETY-RULES.md` → "Required Checklist" 7 bandini yozib o'ting.
- **Buzilmasligi shart bo'lgan qoidalar:** 1 (dual-check idempotency), 4 (MANUAL ack), 8 (`Integer.MAX_VALUE` safety net), 10 (Oracle/API alohida), 23 (CIRCUIT_BREAKER hech qachon `isNonRetryableSource`ga qo'shilmaydi, `attemptCount` oshirilmaydi), 24 (`markTokenEvicted`), 25 (Redis gate xatosida `nack`, hech qachon `ack`).
- **Oracle va API consumer'lari OYNA-NUSXA.** `ApiRequestConsumer`ga qilingan har bir o'zgarish `OracleRequestConsumer`ga ham aynan qilinadi. Ularni birlashtirmang (10-qoida).
- **Yangi metrika har bir yangi kod yo'liga.** 3-qoida: metrikalarni o'chirish taqiqlangan, yangi yo'l metrikasiz qolmasligi kerak.
- Har commit `debug` branch'da, xabar oxirida `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Yangi config kalitlari `application.yml`ga `${ENV_VAR:default}` shaklida qo'shiladi (Safe Zone), va `ai-context/CONFIGURATION.md`ga yoziladi.

---

## File Structure

| Fayl | Mas'uliyat | Task |
|------|-----------|------|
| `app/src/main/java/uz/greenwhite/gateway/api/ProxyController.java` | REST ingress — `createdAt` o'rnatish | 1 |
| `app/src/main/java/uz/greenwhite/gateway/tenant/TenantMappingService.java` | Source pull ingress — `createdAt` o'rnatish | 1 |
| `app/src/main/java/uz/greenwhite/gateway/state/RequestStateService.java` | Redis: qayta-navbat hisoblagichi (yangi metodlar) | 2 |
| `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java` | CB cap + qulf raqobatida nack | 2, 4 |
| `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java` | Yuqoridagining oyna-nusxasi | 2, 4 |
| `app/src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java` | Yangi 2 metrika | 2, 4 |
| `app/src/main/java/uz/greenwhite/gateway/config/RetryProperties.java` | `cbMaxRequeues` xossasi | 2 |
| `app/src/main/java/uz/greenwhite/gateway/config/KafkaConfig.java` | `setAsyncAcks(true)` | 3 |
| `app/src/main/java/uz/greenwhite/gateway/concurrency/DynamicConcurrencyManager.java` | stop/start olib tashlash | 5 |
| `app/src/main/java/uz/greenwhite/gateway/concurrency/ConcurrencyProperties.java` | `allowRestartOnScaleUp` flagi | 5 |
| `app/src/main/resources/application.yml` | Yangi 2 config kaliti | 2, 5 |
| `ai-context/{SAFETY-RULES,CONFIGURATION,ARCHITECTURE}.md` | Hujjatlash | 6 |

---

### Task 1: `createdAt` ikkala ingress nuqtasida — o'lik xavfsizlik to'rini tiriltirish

> **✅ BAJARILDI** — commit `c3ff6dd`. Amalga oshirilgan test fayllari rejadagidan farq qiladi:
> `TenantMappingServiceTest.toInternalStampsCreatedAtWhenSourceOmitsIt` (mavjud faylga qo'shildi,
> JUnit assertion uslubi) + `ProxyControllerCreatedAtTest` (YANGI — rejadagi `MessageExpiryTest`
> o'rniga: u faqat `LocalDateTime` arifmetikasini tekshirar edi, buning o'rniga `handleRequest`
> haqiqiy yo'li `ArgumentCaptor` bilan tekshiriladi, ya'ni ingress muhri kafolatlanadi).
> Natija: app moduli 185/185 yashil (183 + 2).
> **Muhit eslatmasi:** `app` moduli `frontend` profilini default yoqadi va `npm ci` ishlatadi;
> tez test uchun `-P '!frontend'` bering (bash'dan; PowerShell `!`ni buzadi). Surefire ko'p test
> uchun ajratkich — vergul: `-Dtest='A,B'`.

**Muammo:** `RequestMessage.createdAt` prod kodda hech qayerda o'rnatilmaydi (faqat dev `TestController`da). `isMessageExpired` esa `createdAt == null` bo'lsa `false` qaytaradi, ya'ni `retry.max-message-age-ms: 300000` sozlamasi hech qachon ishlamagan. 23- va 24-qoidalar shu to'rni yagona backstop deb ataydi.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/api/ProxyController.java` (RequestMessage builder, ~193-210)
- Modify: `app/src/main/java/uz/greenwhite/gateway/tenant/TenantMappingService.java:66-76` (`toInternal`)
- Test: `app/src/test/java/uz/greenwhite/gateway/tenant/TenantMappingServiceTest.java` (mavjud faylga qo'shish)
- Test: `app/src/test/java/uz/greenwhite/gateway/kafka/consumer/MessageExpiryTest.java` (YANGI)

**Interfaces:**
- Produces: har bir prod yo'lda yaratilgan `RequestMessage`ning `getCreatedAt() != null` bo'lishi. Task 2 shu kafolatga tayanadi (muddat to'ri hisoblagich bilan birga ishlaydi).

- [ ] **Step 1: `toInternal` uchun failing test yozish** — `TenantMappingServiceTest.java`ga qo'shing:

```java
    @Test
    void toInternalStampsCreatedAtWhenSourceOmitsIt() throws Exception {
        // Biruni pull elementida created_at yo'q — ingress o'zi qo'yishi kerak,
        // aks holda isMessageExpired() hech qachon ishlamaydi (SAFETY-RULES 23/24 backstop).
        String json = "{\"company_id\":100,\"request_id\":900123,\"base_url\":\"https://x\",\"method\":\"POST\"}";
        JsonNode node = new ObjectMapper().readTree(json);

        RequestMessage msg = service.toInternal(node);

        assertThat(msg.getCreatedAt()).isNotNull();
        assertThat(msg.getCreatedAt()).isAfter(LocalDateTime.now().minusMinutes(1));
        assertThat(msg.getTenantId()).isEqualTo(100L);
    }
```

Import qatorlariga kerak bo'lsa qo'shing: `java.time.LocalDateTime`, `com.fasterxml.jackson.databind.ObjectMapper`, `com.fasterxml.jackson.databind.JsonNode`.
Mavjud testdagi `service` va `settings` mock qurilishini o'zgartirmang — shu fayldagi mavjud `@BeforeEach` patternidan foydalanadi.

- [ ] **Step 2: Testni ishga tushirib FAIL ko'rish**

Run: `$MVN -B -pl app -Dtest=TenantMappingServiceTest test`
Expected: FAIL — `toInternalStampsCreatedAtWhenSourceOmitsIt`, `expecting actual not to be null`

- [ ] **Step 3: `toInternal`ni tuzatish** — `TenantMappingService.java:71-75` orasiga qo'shing:

```java
        RequestMessage msg = lenientMapper.convertValue(forConversion, RequestMessage.class);
        TenantPair pair = extract(element);
        msg.setTenantId(pair.tenantId());
        msg.setExtraTenantId(pair.extraTenantId());
        // Xabar-muddati to'ri (retry.max-message-age-ms) SHU maydonga tayanadi. Manba
        // created_at yubormaydi, shuning uchun ingress'da muhrlanadi — aks holda
        // isMessageExpired() doim false qaytaradi va CB qayta-navbati cheksiz bo'ladi.
        if (msg.getCreatedAt() == null) {
            msg.setCreatedAt(LocalDateTime.now());
        }
        return msg;
```

`java.time.LocalDateTime` import qilinadi.

- [ ] **Step 4: Test PASS**

Run: `$MVN -B -pl app -Dtest=TenantMappingServiceTest test`
Expected: PASS (barcha mavjud testlar ham)

- [ ] **Step 5: `ProxyController` uchun failing test yozish** — `MessageExpiryTest.java` YANGI fayl:

```java
package uz.greenwhite.gateway.kafka.consumer;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.model.kafka.RequestMessage;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `RequestMessage.createdAt` prod ingress'larda muhrlanishi SHART: `isMessageExpired`
 * null'da false qaytaradi, ya'ni muhrlanmasa SAFETY-RULES 23/24-dagi yagona backstop
 * (retry.max-message-age-ms) o'lik qoladi va CB-blocked qayta-navbat cheksiz aylanadi.
 */
class MessageExpiryTest {

    @Test
    void expiredWhenOlderThanMaxAge() throws Exception {
        RequestMessage msg = RequestMessage.builder()
                .tenantId(1L).requestId(2L)
                .createdAt(LocalDateTime.now().minusMinutes(10))
                .build();
        assertThat(ageMs(msg)).isGreaterThan(300_000L);
    }

    @Test
    void notExpiredWhenFresh() {
        RequestMessage msg = RequestMessage.builder()
                .tenantId(1L).requestId(2L)
                .createdAt(LocalDateTime.now())
                .build();
        assertThat(ageMs(msg)).isLessThan(300_000L);
    }

    @Test
    void nullCreatedAtIsTheBugWeAreFixing() {
        // Dokumentatsiya-test: createdAt null bo'lsa yosh hisoblanmaydi, ya'ni
        // to'r ishlamaydi. Ingress'lar buni hech qachon null qoldirmasligi kerak.
        RequestMessage msg = RequestMessage.builder().tenantId(1L).requestId(2L).build();
        assertThat(msg.getCreatedAt()).isNull();
    }

    private long ageMs(RequestMessage m) {
        return java.time.Duration.between(m.getCreatedAt(), LocalDateTime.now()).toMillis();
    }
}
```

- [ ] **Step 6: Testni ishga tushirish**

Run: `$MVN -B -pl app -Dtest=MessageExpiryTest test`
Expected: PASS (bu regressiya-qulf; 3 test)

- [ ] **Step 7: `ProxyController` builder'iga `createdAt` qo'shish** — `ProxyController.java`da `RequestMessage.builder()` chaqirig'ini toping (~193-qator, `.baseUrl(settingsService.get("gateway.api.base-url", ...))` bor joy) va `.createdAt(...)` qatorini qo'shing:

```java
                .contentType(contentType)
                .callbackUrl(callbackUrl)
                .createdAt(java.time.LocalDateTime.now())
                .build();
```

MUHIM: mavjud maydonlarning birortasini o'chirmang yoki tartibini o'zgartirmang — faqat `.createdAt(...)` qatorini `.build()`dan oldin qo'shing. Agar `.createdAt(` allaqachon bor bo'lsa, hech narsa qo'shmang.

- [ ] **Step 8: To'liq app testlari**

Run: `$MVN -B -pl app test`
Expected: PASS — 183+ test. `ProxyController`ga tegishli mavjud testlar (`ProxyControllerIpTest` va h.k.) yashil qolishi kerak.

- [ ] **Step 9: SAFETY-RULES checklist'ni yozib o'tish**

Idempotency: tegilmagan. Retry: tegilmagan (faqat to'r tiriladi). Metrics: yangi yo'l yo'q. DLQ: muddat o'tgan xabar allaqachon `handlePermanentFailure` → DLQ. Redis: tegilmagan. MANUAL ack: tegilmagan. Thread safety: `LocalDateTime.now()` thread-safe.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/api/ProxyController.java \
        app/src/main/java/uz/greenwhite/gateway/tenant/TenantMappingService.java \
        app/src/test/java/uz/greenwhite/gateway/tenant/TenantMappingServiceTest.java \
        app/src/test/java/uz/greenwhite/gateway/kafka/consumer/MessageExpiryTest.java
git commit -m "fix(pipeline): createdAt ikkala ingress'da muhrlanadi — xabar-muddati to'ri tiriltirildi

max-message-age-ms sozlangan bo'lsa-da createdAt prod'da hech qachon
o'rnatilmagani uchun isMessageExpired doim false qaytargan. SAFETY-RULES
23/24 shu to'rni CB qayta-navbat va OAuth2 evict yo'llarining yagona
backstop'i deb ataydi — 2026-05-18 Redpanda 57GB avariyasining sababi.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: CB qayta-navbat hisoblagichi — 5 daqiqa ichidagi yozuv-kuchaytirishini to'xtatish

**Muammo:** Task 1'dan keyin ham CB OPEN bo'lganda xabar 5 daqiqa davomida Kafka round-trip tezligida (~20 ms) aylanadi — bitta xabar uchun ~15 000 yangi Kafka yozuvi. 1000 navbatdagi xabar bilan bu hali ham diskni to'ldiradi. 23-qoida `attemptCount` oshirishni va `isNonRetryableSource`ga CIRCUIT_BREAKER qo'shishni TAQIQLAYDI, lekin **alohida** qayta-navbat hisoblagichini taqiqlamaydi.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/state/RequestStateService.java` (yangi 2 metod)
- Modify: `app/src/main/java/uz/greenwhite/gateway/config/RetryProperties.java` (yangi xossa)
- Modify: `app/src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java` (yangi metrika)
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java:211-216`
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java` (aynan shu joy)
- Modify: `app/src/main/resources/application.yml:142-146` (`retry:` bloki)
- Test: `app/src/test/java/uz/greenwhite/gateway/state/RequestStateRequeueCountTest.java` (YANGI)

**Interfaces:**
- Produces: `RequestStateService.incrementCbRequeue(String key)` → `int` (yangi qiymat; Redis xatosida `Integer.MAX_VALUE` — 8-qoida bilan izchil fail-closed). `RetryProperties.getCbMaxRequeues()` → `int`. `GatewayMetrics.getCbRequeueExhausted()` → `Counter`.

- [ ] **Step 1: Failing test yozish** — `RequestStateRequeueCountTest.java` YANGI fayl:

```java
package uz.greenwhite.gateway.state;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uz.greenwhite.gateway.config.RedisProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CB qayta-navbat hisoblagichi: cheksiz halqani sanab to'xtatadi. Redis xatosida
 * Integer.MAX_VALUE (fail-closed) — SAFETY-RULES 8-qoidasi bilan bir xil pattern.
 */
class RequestStateRequeueCountTest {

    @SuppressWarnings("unchecked")
    private RequestStateService svc(ValueOperations<String, Object> ops, boolean redisBroken) {
        RedisTemplate<String, Object> tpl = mock(RedisTemplate.class);
        if (redisBroken) {
            when(tpl.opsForValue()).thenThrow(new RuntimeException("redis down"));
        } else {
            when(tpl.opsForValue()).thenReturn(ops);
        }
        RedisProperties props = new RedisProperties();
        return new RequestStateService(tpl, props, mock(GatewayMetrics.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementReturnsGrowingCount() {
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);
        RequestStateService svc = svc(ops, false);

        assertThat(svc.incrementCbRequeue("1:2")).isEqualTo(1);
        assertThat(svc.incrementCbRequeue("1:2")).isEqualTo(2);
        assertThat(svc.incrementCbRequeue("1:2")).isEqualTo(3);
    }

    @Test
    void incrementFailsClosedWhenRedisDown() {
        // Redis o'chiq bo'lsa DARHOL cap oshgan hisoblanadi -> DLQ, cheksiz halqa YO'Q.
        RequestStateService svc = svc(null, true);
        assertThat(svc.incrementCbRequeue("1:2")).isEqualTo(Integer.MAX_VALUE);
    }
}
```

Konstruktor imzosi mos kelmasa: `RequestStateService`ning haqiqiy konstruktorini o'qib, mock argumentlarini shunga moslang — mavjud `RequestStateService*Test` fayllaridagi qurish usulini nusxalang.

- [ ] **Step 2: FAIL ko'rish**

Run: `$MVN -B -pl app -Dtest=RequestStateRequeueCountTest test`
Expected: COMPILE FAIL — `cannot find symbol: method incrementCbRequeue`

- [ ] **Step 3: `RequestStateService`ga metodlar qo'shish** — mavjud `markTokenEvicted`/`isTokenEvicted` metodlari yonига (ular ~248-273 qatorlarda) qo'shing. Kalit prefiksini fayl boshidagi konstantalar yonига qo'shing:

```java
    private static final String CB_REQUEUE_PREFIX = "request:cb-requeue:";
```

va metod:

```java
    /**
     * CB-blocked qayta-navbatlar sonini oshiradi va yangi qiymatni qaytaradi.
     * SAFETY-RULES 23: attemptCount OSHIRILMAYDI — bu alohida, faqat cheksiz
     * halqani sanab to'xtatish uchun hisoblagich. Redis xatosida
     * Integer.MAX_VALUE qaytaradi (fail-closed, 8-qoida bilan bir xil) —
     * Redis o'chganda halqa emas, darhol DLQ.
     * TTL request state TTL'i bilan bir xil, ya'ni so'rov hayoti bilan o'ladi.
     */
    public int incrementCbRequeue(String compositeId) {
        try {
            String k = CB_REQUEUE_PREFIX + compositeId;
            Long v = redisTemplate.opsForValue().increment(k);
            redisTemplate.expire(k, Duration.ofHours(redisProperties.getStateTtlHours()));
            return v == null ? Integer.MAX_VALUE : v.intValue();
        } catch (Exception e) {
            log.error("Redis unavailable for CB requeue count {} — failing closed: {}",
                    compositeId, e.getMessage());
            return Integer.MAX_VALUE;
        }
    }
```

`redisProperties` maydon nomi shu klassdagi haqiqiy nom bilan mos bo'lishi kerak (mavjud `stateTtlHours` ishlatilgan joyni nusxalang, masalan `saveState` metodidan). `java.time.Duration` allaqachon import qilingan bo'lishi ehtimoli yuqori.

- [ ] **Step 4: Test PASS**

Run: `$MVN -B -pl app -Dtest=RequestStateRequeueCountTest test`
Expected: PASS (2 test)

- [ ] **Step 5: `RetryProperties`ga xossa qo'shish** — `RetryProperties.java`ga mavjud maydonlar yonига:

```java
    /** CB-blocked qayta-navbatlarning maksimal soni; oshsa DLQ (SAFETY-RULES 23 to'ri). */
    private int cbMaxRequeues = 50;
```

- [ ] **Step 6: `application.yml`ga kalit qo'shish** — `retry:` bloki (142-146 qatorlar) oxiriga:

```yaml
    cb-max-requeues: ${RETRY_CB_MAX_REQUEUES:50}
```

- [ ] **Step 7: `GatewayMetrics`ga metrika qo'shish** — mavjud counter'lar patternini nusxalab (`httpRetry` yonида):

```java
    private final Counter cbRequeueExhausted;
```

va konstruktor/init blokida:

```java
        this.cbRequeueExhausted = Counter.builder("gateway.http.circuitbreaker.requeue.exhausted")
                .description("CB-blocked messages sent to DLQ after exceeding cb-max-requeues")
                .register(registry);
```

Getter Lombok `@Getter` orqali kelsa qo'shimcha hech narsa kerak emas; aks holda mavjud getter patternini nusxalang.

- [ ] **Step 8: `ApiRequestConsumer`da CB shoxini yangilash** — `ApiRequestConsumer.java:211-216`ni ALMASHTIRING:

```java
        if ("CIRCUIT_BREAKER".equals(errorSourceStr)) {
            // SAFETY-RULES 23: CB — transient back-pressure, attemptCount OSHIRILMAYDI
            // va CIRCUIT_BREAKER hech qachon isNonRetryableSource'ga qo'shilmaydi.
            // Lekin cheksiz halqa bo'lmasligi uchun qayta-navbatlar SANALADI:
            // cap oshsa xabar DLQ'ga ketadi (v1.0.8'dagi "har so'rovga bitta alert"
            // muammosi qaytmaydi, chunki cap faqat HAQIQATAN tiqilib qolgan
            // xabarlarga yetadi).
            int requeues = requestStateService.incrementCbRequeue(key);
            if (requeues > retryProperties.getCbMaxRequeues()) {
                metrics.getCbRequeueExhausted().increment();
                log.error("[API] CB requeue cap exceeded ({} > {}), sending to DLQ: {}",
                        requeues, retryProperties.getCbMaxRequeues(), key);
                handlePermanentFailure(key, message, httpStatus,
                        "Circuit breaker still open after " + requeues + " requeues", ErrorSource.SYSTEM);
                return;
            }
            metrics.getHttpRetry().increment();
            log.warn("[API] CB-blocked, requeueing without attempt increment ({}/{}): {}",
                    requeues, retryProperties.getCbMaxRequeues(), key);
            requestProducer.sendApiRequest(message);
            return;
        }
```

`ErrorSource` allaqachon import qilingan (muddat-to'ri yo'lida ishlatiladi). `handlePermanentFailure` imzosi shu klassdagi mavjud chaqiruv bilan bir xil bo'lishi kerak — 126-qatordagi chaqiruvni namuna qiling.

- [ ] **Step 9: `OracleRequestConsumer`da AYNAN shu o'zgarish** — `OracleRequestConsumer.java`dagi mos CB shoxini toping va yuqoridagi bilan bir xil qilib almashtiring, faqat:
  - log prefiksi `[API]` → `[ORACLE]`
  - `requestProducer.sendApiRequest(message)` → `requestProducer.sendOracleRequest(message)`

Ikkala klassni birlashtirmang (10-qoida).

- [ ] **Step 10: To'liq app testlari**

Run: `$MVN -B -pl app test`
Expected: PASS. Agar CB xatti-harakatini tekshiruvchi mavjud test bo'lsa (`grep -rl "CIRCUIT_BREAKER" app/src/test`), u yangi cap bilan ham yashil qolishi kerak — cap 50, testlar bitta qayta-navbat tekshiradi.

- [ ] **Step 11: SAFETY-RULES checklist**

Idempotency: tegilmagan. Retry: `attemptCount` OSHIRILMAGAN (23-qoida saqlandi), CIRCUIT_BREAKER `isNonRetryableSource`ga qo'shilmagan. Metrics: yangi `cbRequeueExhausted` qo'shildi. DLQ: cap oshganda DLQ — jim tashlash yo'q. Redis: `Integer.MAX_VALUE` fail-closed → darhol DLQ. MANUAL ack: ack chaqiruvchi `finally`da, tegilmagan. Thread safety: Redis `INCR` atomik.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/state/RequestStateService.java \
        app/src/main/java/uz/greenwhite/gateway/config/RetryProperties.java \
        app/src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java \
        app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java \
        app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java \
        app/src/main/resources/application.yml \
        app/src/test/java/uz/greenwhite/gateway/state/RequestStateRequeueCountTest.java
git commit -m "fix(pipeline): CB qayta-navbat cap'i — yozuv-kuchaytirish storm'i to'xtatildi

CB OPEN bo'lganda xabar Kafka round-trip tezligida aylanardi. attemptCount
oshirilmaydi (SAFETY-RULES 23 saqlandi), lekin alohida Redis hisoblagichi
cap'dan oshsa xabar DLQ'ga ketadi. Redis o'chiq bo'lsa fail-closed.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: `setAsyncAcks(true)` — tartibsiz offset commit'ini to'xtatish

**Muammo:** Consumer'lar `AckMode.MANUAL` ishlatadi (4-qoida bo'yicha to'g'ri), lekin ish `CompletableFuture.runAsync` bilan bajarilib ack keyinroq, tartibsiz keladi. Spring Kafka `MANUAL` rejimida faqat eng katta ack qilingan offset'ni commit qiladi; tartibsiz ack'lar uchun `setAsyncAcks(true)` shart, u offset bo'shliqlari to'lguncha commit'ni kechiktiradi.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/config/KafkaConfig.java:158-168` (`buildFactory`)
- Test: `app/src/test/java/uz/greenwhite/gateway/config/KafkaAsyncAcksTest.java` (YANGI)

**Interfaces:**
- Produces: barcha 5 container factory'da `getContainerProperties().isAsyncAcks() == true` va `getAckMode() == MANUAL`.

- [ ] **Step 1: Failing test yozish** — `KafkaAsyncAcksTest.java` YANGI fayl:

```java
package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer'lar ack'ni asinxron (CompletableFuture) yo'ldan chaqiradi, ya'ni ack'lar
 * offset tartibida kelmaydi. MANUAL rejimida bu asyncAcks=true bo'lmasa tartibsiz
 * commit'ga va jim xabar yo'qolishiga olib keladi. SAFETY-RULES 4: MANUAL o'zgarmaydi.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:async_acks_test;DB_CLOSE_DELAY=-1",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.rocksdb.path=./target/test-data/rocksdb-async-acks"
})
class KafkaAsyncAcksTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("apiRequestConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> apiRequest;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("oracleRequestConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> oracleRequest;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("apiResponseConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> apiResponse;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("oracleResponseConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> oracleResponse;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("dlqConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> dlq;

    @Test
    void everyFactoryIsManualWithAsyncAcks() {
        for (ConcurrentKafkaListenerContainerFactory<String, Object> f :
                java.util.List.of(apiRequest, oracleRequest, apiResponse, oracleResponse, dlq)) {
            ContainerProperties p = f.getContainerProperties();
            assertThat(p.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
            assertThat(p.isAsyncAcks()).isTrue();
        }
    }
}
```

- [ ] **Step 2: FAIL ko'rish**

Run: `$MVN -B -pl app -Dtest=KafkaAsyncAcksTest test`
Expected: FAIL — `Expecting value to be true but was false` (asyncAcks)

- [ ] **Step 3: `buildFactory`ga bitta qator qo'shish** — `KafkaConfig.java:163`dan keyin:

```java
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Consumer'lar ack'ni CompletableFuture'dan, offset tartibisiz chaqiradi.
        // asyncAcks offset bo'shliqlari to'lguncha commit'ni kechiktiradi — busiz
        // tez tugagan katta offset sekin tugayotgan kichigini "commit qilingan"
        // qilib ko'rsatadi va restart'da u qayta yetkazilmaydi (jim yo'qolish).
        // SAFETY-RULES 4: AckMode MANUAL o'zgarmaydi, asyncAcks faqat MANUAL bilan ishlaydi.
        factory.getContainerProperties().setAsyncAcks(true);
```

- [ ] **Step 4: Test PASS**

Run: `$MVN -B -pl app -Dtest=KafkaAsyncAcksTest test`
Expected: PASS

- [ ] **Step 5: To'liq app testlari**

Run: `$MVN -B -pl app test`
Expected: PASS — barcha mavjud Kafka testlari yashil.

- [ ] **Step 6: SAFETY-RULES checklist**

Idempotency: tegilmagan. Retry: tegilmagan. Metrics: yangi yo'l yo'q. DLQ: tegilmagan. Redis: tegilmagan. MANUAL ack: SAQLANDI (asyncAcks faqat MANUAL bilan birga ishlaydi). Thread safety: konfiguratsiya, boot paytida bir marta.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/config/KafkaConfig.java \
        app/src/test/java/uz/greenwhite/gateway/config/KafkaAsyncAcksTest.java
git commit -m "fix(kafka): setAsyncAcks(true) — tartibsiz offset commit'i tuzatildi

Consumer'lar ack'ni CompletableFuture'dan chaqiradi, ya'ni tartibsiz. MANUAL
rejimida asyncAcks bo'lmasa tez tugagan katta offset sekin kichigini yopib
ketadi va restart'da u qayta yetkazilmaydi. AckMode MANUAL o'zgarmadi.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Qulf raqobatida `nack` — restart'dan keyin xabar tashlanishini to'xtatish

**Muammo:** `tryLock` false qaytarsa kod `ack.acknowledge()` qilib xabarni TASHLAYDI. Izohda "boshqa instansiya ishlayapti" deyilgan, lekin bitta instansiyali prod'da restart'dan keyin bu noto'g'ri: orfan qulflar 300 sekund yashaydi va shu vaqtda qayta yetkazilgan barcha xabarlar yo'qoladi. 25-qoida Redis gate XATOSIDA `nack` talab qiladi; qulf RAQOBATI ham xuddi shunday muomalaga loyiq.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java:86-91`
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java` (aynan shu joy)
- Test: `app/src/test/java/uz/greenwhite/gateway/kafka/consumer/LockContentionNackTest.java` (YANGI)

**Interfaces:**
- Consumes: mavjud `Acknowledgment` API (`nack(Duration)`), `RequestStateService.tryLock`.
- Produces: qulf olinmaganda `ack.nack(Duration.ofSeconds(10))`, `ack.acknowledge()` EMAS.

- [ ] **Step 1: Failing test yozish** — `LockContentionNackTest.java` YANGI fayl. Mavjud consumer unit-testlarining qurish usulini nusxalang (`ls app/src/test/java/uz/greenwhite/gateway/kafka/consumer/`), va mock'lar to'plamini shu fayldan oling:

```java
package uz.greenwhite.gateway.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import uz.greenwhite.gateway.model.kafka.RequestMessage;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qulf raqobatida xabar ACK QILINMASLIGI kerak. Bitta instansiyali prod'da
 * restart'dan keyin orfan qulflar 300s yashaydi — ack qilish o'sha oynada
 * kelgan barcha xabarlarni jim yo'qotadi (SAFETY-RULES 25 ruhi).
 */
class LockContentionNackTest {

    @Test
    void lockContentionNacksInsteadOfAcking() {
        // MOCK QURISH: mavjud ApiRequestConsumer unit-testidan @BeforeEach blokini
        // nusxalab, shu yerda consumer + mock'larni qurib oling. Keyin:
        //   when(requestStateService.isCompleted("1:2")).thenReturn(false);
        //   when(requestStateService.tryLock("1:2")).thenReturn(false);
        //   consumer.consumeRequest(record, ack);
        //   verify(ack).nack(any(Duration.class));
        //   verify(ack, never()).acknowledge();
        //   verify(requestProducer, never()).sendApiRequest(any());
    }
}
```

MUHIM — bu yagona joy: yuqoridagi test tanasi izoh shaklida. Uni bajaruvchi mavjud `ApiRequestConsumer` unit-testini o'qib (agar yo'q bo'lsa, `@SpringBootTest` o'rniga to'g'ridan-to'g'ri konstruktor bilan quring) haqiqiy mock qurishni yozadi. Agar `ApiRequestConsumer` uchun unit-test mavjud bo'lmasa, konstruktor imzosini o'qib barcha bog'liqliklarni `mock(...)` bilan bering va `httpExecutor` uchun `java.util.concurrent.Executors.newSingleThreadExecutor()` ishlating.

- [ ] **Step 2: FAIL ko'rish**

Run: `$MVN -B -pl app -Dtest=LockContentionNackTest test`
Expected: FAIL — `Wanted but not invoked: ack.nack(...)`, aksincha `acknowledge()` chaqirilgan

- [ ] **Step 3: `ApiRequestConsumer`ni tuzatish** — `86-91` qatorlarni ALMASHTIRING:

```java
            if (!requestStateService.tryLock(key)) {
                // Qulf band. Bu HAR DOIM "boshqa instansiya ishlayapti" degani EMAS:
                // restart/SIGKILL'dan keyin o'lgan instansiyaning qulflari lock TTL
                // (300s) tugaguncha orfan qoladi. Ack qilish o'sha oynadagi barcha
                // qayta yetkazilgan xabarlarni jim yo'qotadi — nack qilib Kafka'ga
                // qaytaramiz, egasi tugatgach yoki TTL o'tgach qayta urinadi.
                log.warn("[API] Lock held for {} — nack, will redeliver", key);
                metrics.getConsumerLockFailed().increment();
                ack.nack(Duration.ofSeconds(10));
                return;
            }
```

`Duration` allaqachon import qilingan (95-qatorda ishlatiladi).

- [ ] **Step 4: `OracleRequestConsumer`da AYNAN shu o'zgarish** — log prefiksi `[ORACLE]`, qolgani bir xil.

- [ ] **Step 5: Test PASS**

Run: `$MVN -B -pl app -Dtest=LockContentionNackTest test`
Expected: PASS

- [ ] **Step 6: To'liq app testlari**

Run: `$MVN -B -pl app test`
Expected: PASS. DIQQAT: `consumerLockFailed` metrikasini yoki qulf-raqobat yo'lini tekshiruvchi mavjud test `acknowledge()` kutayotgan bo'lishi mumkin — uni yangi semantikaga (`nack`) moslang va izohda nima uchun o'zgarganini yozing.

- [ ] **Step 7: SAFETY-RULES checklist**

Idempotency: dual-check SAQLANDI (`isCompleted` + `tryLock` o'zgarmadi). Retry: Kafka qayta yetkazishi orqali, cheksiz emas — egasi tugatsa `isCompleted` true bo'ladi va ack bo'ladi. Metrics: mavjud `consumerLockFailed` saqlandi. DLQ: jim tashlash O'CHIRILDI. Redis: `tryLock` exception'i yuqoridagi catch'da (25-qoida) — o'zgarmadi. MANUAL ack: `nack` ham MANUAL rejimning qismi. Thread safety: listener thread'da, `nack` shu yerda chaqirilishi shart (async yo'lda emas) — shunday.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java \
        app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java \
        app/src/test/java/uz/greenwhite/gateway/kafka/consumer/LockContentionNackTest.java
git commit -m "fix(pipeline): qulf raqobatida nack — restart'dan keyin xabar tashlanmaydi

tryLock false bo'lganda ack.acknowledge() xabarni tashlardi. Bitta
instansiyali prod'da restart'dan keyin orfan qulflar 300s yashaydi, ya'ni
o'sha oynadagi barcha qayta yetkazilgan xabarlar jim yo'qolardi.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Dinamik masshtablashda stop/start'ni olib tashlash

**Muammo:** `DynamicConcurrencyManager` lag oshganda ishlab turgan konteynerni `stop()` keyin `start()` qiladi. Bu group-rebalance keltiradi; to'xtatilgandan keyin kelgan in-flight ack'lar bekor bo'ladi va uncommitted offset'lar Task 4 yo'liga (orfan qulf) tushadi. Kodning O'Z IZOHI (107-108 qatorlar) "stop/start emas, keyingi rebalance'da qo'llaniladi" deydi — kod izohiga qarama-qarshi.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/concurrency/DynamicConcurrencyManager.java:103-125`
- Modify: `app/src/main/java/uz/greenwhite/gateway/concurrency/ConcurrencyProperties.java` (yangi flag)
- Modify: `app/src/main/resources/application.yml` (`concurrency:` bloki)
- Test: `app/src/test/java/uz/greenwhite/gateway/concurrency/NoRestartOnScaleUpTest.java` (YANGI)

**Interfaces:**
- Produces: `ConcurrencyProperties.isAllowRestartOnScaleUp()` → `boolean`, default `false`.

- [ ] **Step 1: Failing test yozish** — `NoRestartOnScaleUpTest.java` YANGI fayl:

```java
package uz.greenwhite.gateway.concurrency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale-up konteynerni stop()/start() qilmasligi kerak: bu group-rebalance
 * keltiradi va to'xtagandan keyin kelgan in-flight ack'lar bekor bo'ladi
 * (yuk ostida xabar yo'qolishi). Default: restart TAQIQLANGAN.
 */
class NoRestartOnScaleUpTest {

    @Test
    void restartOnScaleUpIsDisabledByDefault() {
        assertThat(new ConcurrencyProperties().isAllowRestartOnScaleUp()).isFalse();
    }
}
```

- [ ] **Step 2: FAIL ko'rish**

Run: `$MVN -B -pl app -Dtest=NoRestartOnScaleUpTest test`
Expected: COMPILE FAIL — `cannot find symbol: method isAllowRestartOnScaleUp`

- [ ] **Step 3: `ConcurrencyProperties`ga flag qo'shish**

```java
    /**
     * Scale-up'da konteynerni stop()/start() qilishga ruxsat. DEFAULT false:
     * restart group-rebalance keltiradi va to'xtagandan keyin kelgan in-flight
     * ack'lar bekor bo'ladi -> yuk ostida xabar yo'qolishi. false bo'lganda
     * yangi concurrency qiymati faqat keyingi tabiiy rebalance'da/restart'da
     * kuchga kiradi (setConcurrency chaqirilaveradi).
     */
    private boolean allowRestartOnScaleUp = false;
```

- [ ] **Step 4: Test PASS**

Run: `$MVN -B -pl app -Dtest=NoRestartOnScaleUpTest test`
Expected: PASS

- [ ] **Step 5: `application.yml`ga kalit qo'shish** — `concurrency:` bloki (~148-158 qatorlar) oxiriga:

```yaml
    allow-restart-on-scale-up: ${CONCURRENCY_ALLOW_RESTART:false}
```

- [ ] **Step 6: `DynamicConcurrencyManager`ni tuzatish** — `109-113` qatorlarni ALMASHTIRING:

```java
            if (newConcurrency > current) {
                if (properties.isAllowRestartOnScaleUp()) {
                    // Faqat operator ataylab yoqqanda. Restart group-rebalance
                    // keltiradi va to'xtagandan keyin kelgan ack'lar bekor bo'ladi.
                    concurrent.stop();
                    concurrent.start();
                } else {
                    log.info("Scale-up target set for [{}] but restart is disabled — "
                            + "new concurrency {} applies on next rebalance/restart",
                            listenerId, newConcurrency);
                }
            }
```

- [ ] **Step 7: To'liq app testlari**

Run: `$MVN -B -pl app test`
Expected: PASS. `DynamicConcurrencyManager`ni tekshiruvchi mavjud test scale-up'da `stop()`/`start()` kutayotgan bo'lsa, flagni test ichida `true` qilib eski xatti-harakatni tekshiradigan qilib moslang va default-false uchun alohida test qoldiring.

- [ ] **Step 8: SAFETY-RULES checklist**

Idempotency: tegilmagan. Retry: tegilmagan. Metrics: yangi yo'l yo'q (log qo'shildi). DLQ: tegilmagan. Redis: tegilmagan. MANUAL ack: bilvosita himoyalandi (rebalance kamaydi). Thread safety: `AtomicInteger`/`AtomicLong` o'zgarmadi.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/concurrency/DynamicConcurrencyManager.java \
        app/src/main/java/uz/greenwhite/gateway/concurrency/ConcurrencyProperties.java \
        app/src/main/resources/application.yml \
        app/src/test/java/uz/greenwhite/gateway/concurrency/NoRestartOnScaleUpTest.java
git commit -m "fix(concurrency): scale-up'da stop/start default o'chirildi

Ishlab turgan konteynerni stop()/start() qilish group-rebalance keltirardi va
to'xtagandan keyin kelgan in-flight ack'lar bekor bo'lardi — yo'qotish aynan
yuk yuqori bo'lganda. Kodning o'z izohi ham shuni talab qilgan.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: To'liq verifikatsiya + hujjatlash

**Files:**
- Modify: `ai-context/SAFETY-RULES.md` (23-qoidani yangilash + 2 yangi qoida)
- Modify: `ai-context/CONFIGURATION.md` (2 yangi config kaliti)
- Modify: `ai-context/ARCHITECTURE.md` (ack/muddat to'ri tavsifi)
- Modify: `docs/manual-test-plan-v1.14.md` (H1-H4 bandlariga izoh)

- [ ] **Step 1: `SAFETY-RULES.md` 23-qoidasini yangilash** — mavjud 23-qoida matnining oxiriga qo'shing:

```markdown
**v1.15.0 tuzatishi:** bu to'r 2026-07-24'gacha ISHLAMAGAN — `RequestMessage.createdAt`
prod ingress'larda hech qachon o'rnatilmagan va `isMessageExpired` null'da `false`
qaytargan, ya'ni CB-blocked qayta-navbat cheksiz aylanган (2026-05-18 Redpanda 57GB
avariyasi). Endi `createdAt` `ProxyController` va `TenantMappingService.toInternal`da
muhrlanadi — **ikkalasini ham hech qachon olib tashlamang**. Qo'shimcha himoya:
`retry.cb-max-requeues` (default 50) qayta-navbatlarni Redis'da sanaydi va cap'dan
oshsa DLQ'ga yuboradi; `attemptCount` HALI HAM oshirilmaydi va CIRCUIT_BREAKER
`isNonRetryableSource`ga qo'shilmaydi.
```

- [ ] **Step 2: `SAFETY-RULES.md`ga 32- va 33-qoidalarni qo'shish** — 31-qoidadan keyin:

```markdown
### 32. Never remove `setAsyncAcks(true)` from `KafkaConfig.buildFactory`

Consumer'lar `ack`ni `CompletableFuture` yo'lidan, offset tartibisiz chaqiradi.
`AckMode.MANUAL` faqat eng katta ack qilingan offset'ni commit qiladi, shuning uchun
`asyncAcks` bo'lmasa tez tugagan katta offset sekin tugayotgan kichigini "commit
qilingan" qilib ko'rsatadi va restart'da u qayta yetkazilmaydi — jim xabar yo'qolishi,
DLQ'siz, alert'siz. `asyncAcks` faqat MANUAL bilan ishlaydi; 4-qoida bilan birga oling.

### 33. Never ack a request message on Redis lock contention

`tryLock` false qaytarganda `ack.acknowledge()` QILMANG — `ack.nack(Duration.ofSeconds(10))`
qiling. "Boshqa instansiya ishlayapti" faqat ko'p-instansiyali barqaror holatda to'g'ri;
restart/SIGKILL'dan keyin o'lgan instansiyaning qulflari lock TTL (300s) tugaguncha orfan
qoladi va ack qilish o'sha oynadagi barcha qayta yetkazilgan xabarlarni jim yo'qotadi.
25-qoida Redis gate XATOSI uchun, bu qoida qulf RAQOBATI uchun.
```

- [ ] **Step 3: Protected Zones jadvaliga qator qo'shish** — `DynamicConcurrencyManager.java` uchun:

```markdown
| `DynamicConcurrencyManager.java` | Lag-asosli concurrency o'zgartirish; scale-up'da stop/start DEFAULT O'CHIQ (`allow-restart-on-scale-up`) | Restart group-rebalance keltiradi, in-flight ack'lar bekor bo'ladi — yuk ostida xabar yo'qolishi |
```

- [ ] **Step 4: `CONFIGURATION.md`ga 2 kalit qo'shish** — `gateway.retry.*` va `gateway.concurrency.*` jadvallariga:

```markdown
| `gateway.retry.cb-max-requeues` | int | 50 | `RETRY_CB_MAX_REQUEUES` | CB-blocked qayta-navbatlar cap'i; oshsa DLQ (SAFETY-RULES 23) |
| `gateway.concurrency.allow-restart-on-scale-up` | boolean | false | `CONCURRENCY_ALLOW_RESTART` | Scale-up'da konteyner stop/start (rebalance xatari — default o'chiq, SAFETY-RULES 32) |
```

- [ ] **Step 5: `ARCHITECTURE.md`da ack semantikasini yozish** — consumer/E2 bosqichi tavsifiga:

```markdown
**Ack semantikasi:** `AckMode.MANUAL` + `asyncAcks=true`. Ack HTTP javobi kelgandan keyin
reaktiv callback'dan chaqiriladi, ya'ni offset tartibisiz — `asyncAcks` commit'ni offset
bo'shliqlari to'lguncha kechiktiradi. Qulf raqobatida xabar `nack(10s)` bilan Kafka'ga
qaytariladi (ack QILINMAYDI), muddati o'tgan xabar (`retry.max-message-age-ms`, `createdAt`
ingress'da muhrlanadi) va CB cap'idan oshgan xabar (`retry.cb-max-requeues`) DLQ'ga ketadi.
```

- [ ] **Step 6: Ghost-code grep**

Grep tool bilan `app ops settings-core ai-context CLAUDE.md README.md` bo'ylab tekshiring:
`incrementCbRequeue`, `cbMaxRequeues`, `allowRestartOnScaleUp`, `setAsyncAcks` — har biri
kodda VA hujjatda mos nom bilan uchrashi kerak; eski/noto'g'ri nom qolmagani tasdiqlanadi.

- [ ] **Step 7: To'liq reaktor verifikatsiyasi**

Docker infra yoqiq bo'lishini tasdiqlang: `docker compose ps` → redis + redpanda `running`.

Run: `$MVN -B clean install`
Expected: BUILD SUCCESS — settings-core 30+, app 183+ (yangi testlar bilan ~192+), ops 46.

Run: `cd frontend && npm test -- --run`
Expected: 114 test PASS (frontend tegilmagan, regressiya bo'lmasligi kerak).

- [ ] **Step 8: Jonli tekshiruv (Task 1-2 aynan shu yerda isbotlanadi)**

Docker infra yoqiq holda gateway'ni ko'taring va CB halqasini sun'iy yarating:
1. Ops orqali (gateway to'xtiq) `gateway.api.base-url` → `http://127.0.0.1:9` (o'lik port).
2. Gateway'ni yoqing, `POST /qosid/api/v1/queue/x` ni 3 marta yuboring.
3. `docker exec redpanda rpk topic describe gateway.api.request` bilan yozuv sonini kuzating: cap (50) atrofida to'xtashi kerak, cheksiz o'smasligi.
4. `GET /qosid/api/v1/requests/{id}/status` → oxir-oqibat `FAILED`, va DLQ topikida yozuv paydo bo'lishi kerak.
5. `docker exec redis redis-cli KEYS "request:cb-requeue:*"` → hisoblagich kalitlari TTL bilan.
Natijalarni `docs/manual-test-plan-v1.14.md`ning H2 bandiga yozib qo'ying.

- [ ] **Step 9: Commit**

```bash
git add ai-context CLAUDE.md docs/manual-test-plan-v1.14.md
git commit -m "docs: pipeline ishonchlilik tuzatishlari hujjatlashtirildi (SAFETY-RULES 32-33)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review natijasi

**Spec qamrovi:** 2026-07-24 auditidagi 4 ta pipeline CRITICAL'i qoplangan — C1 (createdAt/cheksiz halqa) → Task 1 + Task 2, C2 (asyncAcks) → Task 3, C3 (tryLock ack) → Task 4, C4 (dinamik masshtablash rebalance) → Task 5. Hujjatlash → Task 6. Xavfsizlik CRITICAL'lari (IDOR, admin/admin, git tarixidagi sirlar) ATAYLAB bu rejadan TASHQARIDA — ular alohida reja talab qiladi (boshqa fayllar, boshqa test strategiyasi, tarix qayta yozish esa force-push qarori).

**Placeholder skani:** Task 4 Step 1'da test tanasi ataylab izoh shaklida qoldirilgan, chunki `ApiRequestConsumer` konstruktori 10+ bog'liqlikka ega va uni ko'rmasdan aniq mock qurishni yozish noto'g'ri kod berardi — u yerda bajaruvchiga aniq ko'rsatma berilgan (mavjud unit-testdan `@BeforeEach` nusxalash, aniq `verify` chaqiruvlari yozilgan). Qolgan barcha qadamlarda to'liq kod bor.

**Tip izchilligi:** `incrementCbRequeue(String) → int` (Task 2 Step 3) ↔ `int requeues = requestStateService.incrementCbRequeue(key)` (Step 8) ✓. `getCbMaxRequeues() → int` (Step 5) ↔ `retryProperties.getCbMaxRequeues()` (Step 8) ✓. `getCbRequeueExhausted() → Counter` (Step 7) ↔ `.increment()` (Step 8) ✓. `isAllowRestartOnScaleUp() → boolean` (Task 5 Step 3) ↔ `properties.isAllowRestartOnScaleUp()` (Step 6) ✓ va test (Step 1) ✓. Config kalit nomlari `application.yml` ↔ `CONFIGURATION.md` ↔ Java maydonlari (relaxed binding: `cb-max-requeues` → `cbMaxRequeues`, `allow-restart-on-scale-up` → `allowRestartOnScaleUp`) ✓.

**Xatar eslatmasi:** Task 3 (`asyncAcks`) va Task 4 (`nack`) ikkalasi ham Kafka commit xatti-harakatini o'zgartiradi. Ular ALOHIDA commit qilinadi, shuning uchun prod'da muammo chiqsa bittasini alohida qaytarish mumkin. Task 4 `nack` bir xil partition'da qisqa pauza keltiradi — bu ataylab (back-pressure), lekin agar prod'da qulflar tez-tez band bo'lsa, `Duration`ni kamaytirish (masalan 3s) mumkin.
