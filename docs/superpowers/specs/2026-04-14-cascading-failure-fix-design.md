# Cascading Failure Fix Design

**Date:** 2026-04-14
**Status:** Approved
**Scope:** RequestConsumer retry loop, thread exhaustion, backpressure

## Problem Statement

Production incident: smartup.online API 503 qaytarishi 3 ta muammoni bir-biriga bog'lab, 4.5 million retry va to'liq pipeline stall keltirib chiqardi.

### Root Causes

| # | Sabab | Fayl : Qator | Ta'siri |
|---|-------|-------------|---------|
| 1 | CB OPEN 503 → `handleFailedResponse()` retry → Kafka re-queue → cheksiz loop | `RequestConsumer.java:187-195` | 4.5M retry |
| 2 | `.block(69_000ms)` — har bir thread 69s gacha band | `RequestConsumer.java:122-124` | 30 thread pool to'ladi |
| 3 | `CallerRunsPolicy` — pool to'lganda Kafka consumer thread'ida ishlaydi | `ThreadPoolConfig.java:47` | 10 ta consumer thread ham band |

### Cascade Chain

```
External API 503 → CB opens → CB returns 503
  → handleFailedResponse() sees 503 as retryable → Kafka re-queue
  → consumer picks up again → CB still OPEN → 503 → retry
  → infinite loop (Sabab 1)

Meanwhile:
  → .block(69s) holds thread per request (Sabab 2)
  → 30 threads + 50 queue exhausted
  → CallerRunsPolicy blocks Kafka consumer threads (Sabab 3)
  → pipeline stall
```

## Approach

**Approach B — CB-aware retry + reactive refactor + backpressure fix.**

Barcha 3 ta root cause'ni birga hal qiladi. Minimal blast radius — faqat consumer va config fayllariga tegadi.

## Design

### Fix 1: CB Retry Loop

**Muammo:** `handleFailedResponse()` faqat `httpStatus` ga qaraydi. CB 503 ni oddiy 503 deb retry qiladi.

**Yechim:**

1. `ErrorSource` enum'ga yangi qiymatlar:

```java
enum ErrorSource {
    HTTP("HTTP", "External API error"),
    SYSTEM("SYSTEM", "Internal system error"),
    CIRCUIT_BREAKER("CIRCUIT_BREAKER", "Circuit breaker rejected"),
    OAUTH2("OAUTH2", "OAuth2 token error")
}
```

2. `handleFailedResponse()` da response'dagi `errorSource` string'ni tekshirish:

```java
private void handleFailedResponse(String key, RequestMessage message, ResponseMessage response) {
    int attemptCount = requestStateService.incrementAttempt(key);
    int httpStatus = response != null ? response.getHttpStatus() : 0;
    String errorMessage = response != null ? response.getErrorMessage() : "Unknown error";

    // CB va OAuth2 xatolar — retry qilmaslik, darhol fail
    String errorSource = response != null ? response.getErrorSource() : null;
    boolean isNonRetryableSource = "CIRCUIT_BREAKER".equals(errorSource)
                                 || "OAUTH2".equals(errorSource);

    boolean isRetryable = retryProperties.isRetryable(httpStatus)
                        && !isNonRetryableSource;
    boolean hasAttemptsLeft = attemptCount < retryProperties.getMaxAttempts();

    if (isRetryable && hasAttemptsLeft) {
        // retry logic (existing)
        metrics.getHttpRetry().increment();
        log.warn("E4: Retryable error for {}: status={}, attempt {}/{}",
                key, httpStatus, attemptCount, retryProperties.getMaxAttempts());
        requestProducer.sendRequest(message);
    } else {
        ErrorSource source = parseErrorSource(errorSource);
        handlePermanentFailure(key, message, httpStatus, errorMessage, source);
    }
}
```

3. `parseErrorSource()` helper — string'dan enum'ga xavfsiz parse:

```java
private ErrorSource parseErrorSource(String errorSource) {
    if (errorSource == null) return ErrorSource.HTTP;
    try {
        return ErrorSource.valueOf(errorSource);
    } catch (IllegalArgumentException e) {
        return ErrorSource.HTTP;
    }
}
```

**Natija:** CB OPEN bo'lganda request darhol FAILED bo'ladi, retry loop to'xtaydi.

### Fix 2: `.block()` ni olib tashlash

**Muammo:** `.block(69_000ms)` har bir request uchun thread'ni 69s gacha band qiladi.

**Hozirgi flow:**
```
httpExecutor thread → {
    response = httpRequestService.sendRequest(message).block(69s)  // band!
    handleResponse(response)
    ack.acknowledge()
}
```

**Yangi flow:**
```
httpExecutor thread → {
    httpRequestService.sendRequest(message)
        .timeout(Duration.ofMillis(requestTimeoutMs))  // 30s default
        .subscribe(
            response -> {
                handleResponse(key, message, response, ack);
            },
            error -> {
                handleException(key, message, error, ack);
            }
        );
    // thread darhol bo'shatiladi
}
```

**O'zgarishlar:**
- `.block()` → `.timeout().subscribe()` — thread darhol qaytadi
- Timeout: `gateway.http.request-timeout-ms=30000` (yangi config, default 30s)
- `ack.acknowledge()` subscribe callback ichida — Kafka MANUAL ack saqlanadi
- Exception handling `onError` callback'da

**Xavfsizlik:** Kafka MANUAL ack o'zgarmaydi. Response kelguncha message acknowledge bo'lmaydi.

### Fix 3: CallerRunsPolicy o'zgartirish

**Muammo:** Pool to'lganda `CallerRunsPolicy` Kafka consumer thread'ini block qiladi.

**Yangi xatti-harakat:**
```
Pool to'ldi → task reject → RequestConsumer re-queue qiladi → consumer thread bo'sh
```

**Implementatsiya:**

1. `ThreadPoolConfig` — rejection policy:

```java
executor.setRejectedExecutionHandler((runnable, exec) -> {
    log.warn("HTTP thread pool exhausted! Task rejected. queue={}, active={}, pool={}",
        exec.getQueue().size(), exec.getActiveCount(), exec.getPoolSize());
    throw new RejectedExecutionException("HTTP thread pool exhausted");
});
```

2. `RequestConsumer.consumeRequest()` — rejection handling:

```java
try {
    CompletableFuture.runAsync(() -> processRequest(message, ack), httpExecutor);
} catch (RejectedExecutionException e) {
    log.warn("Pool exhausted, re-queuing: {}", compositeId);
    metrics.getPoolRejection().increment();
    requestProducer.sendRequest(message);
    ack.acknowledge();
}
```

**Natija:** Kafka consumer thread hech qachon band bo'lmaydi.

### Fix 4: Max Attempts kafolati

**Muammo:** Pool rejection re-queue attempt count'ni oshirmasligi kerak (bu haqiqiy retry emas). Lekin message cheksiz aylanmasligi kerak.

**Yechim:**

1. **Ikki turdagi retry ajratish:**
   - HTTP retry (tashqi API xatosi) → `attemptCount` oshadi
   - Pool rejection re-queue → `attemptCount` **oshmasligi** kerak

2. **Message age limit** — yangi config `gateway.retry.max-message-age-ms=300000` (5 minut):

```java
private boolean isMessageExpired(RequestMessage message) {
    if (message.getCreatedAt() == null) return false;
    long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
    return ageMs > retryProperties.getMaxMessageAgeMs();
}
```

3. `processRequest()` boshida expiry tekshirish:

```java
if (isMessageExpired(message)) {
    log.warn("Message expired: {} (age={}ms)", compositeId, messageAge);
    handlePermanentFailure(key, message, 0, "Message expired", ErrorSource.SYSTEM);
    ack.acknowledge();
    return;
}
```

**Natija:** HTTP retry → maxAttempts (3) limiti. Pool rejection → attempt oshmasligi. Message age → 5 minut limitdan keyin fail.

## Tegadigan fayllar

| Fayl | O'zgartirish | Fix |
|------|-------------|-----|
| `ErrorSource.java` | `CIRCUIT_BREAKER`, `OAUTH2` enum values qo'shish | Fix 1 |
| `RequestConsumer.java` | `handleFailedResponse()` errorSource tekshirish, `.block()` → `.subscribe()`, pool rejection handling, message expiry | Fix 1, 2, 3, 4 |
| `ThreadPoolConfig.java` | `CallerRunsPolicy` → reject policy | Fix 3 |
| `RetryProperties.java` | `maxMessageAgeMs` config qo'shish | Fix 4 |
| `application.yml` | `request-timeout-ms`, `max-message-age-ms` parametrlar | Fix 2, 4 |
| `RequestMessage.java` | `createdAt` field (agar yo'q bo'lsa tekshirish kerak) | Fix 4 |

## Tegmaydigan fayllar (xavfsizlik zonasi)

| Fayl | Sabab |
|------|-------|
| `KafkaConfig.java` | MANUAL ack o'zgarmaydi (Safety Rule #1) |
| `HttpRequestService.java` | CB logic o'zgarmaydi (Safety Rule #3) |
| `ResponseConsumer.java` | Source guard va retry logic o'zgarmaydi (Safety Rule #5) |
| `RequestStateService.java` | Integer.MAX_VALUE safety net o'zgarmaydi (Safety Rule #4) |

## Yangi konfiguratsiya parametrlari

| Parametr | Turi | Default | Tavsif |
|----------|------|---------|--------|
| `gateway.http.request-timeout-ms` | long | 30000 | `.subscribe()` timeout (`.block()` o'rniga) |
| `gateway.retry.max-message-age-ms` | long | 300000 | Message maximum yoshi (5 minut), oshsa FAILED |

## Metrikalar

| Metric | Turi | Tavsif |
|--------|------|--------|
| `gateway_pool_rejection_total` | Counter | Pool to'lib reject bo'lgan task'lar soni |

## Test strategiyasi

1. **CB retry loop:** CB ochiq holatda so'rov yuborish → darhol FAILED bo'lishi, retry bo'lmasligi
2. **Thread exhaustion:** Ko'p concurrent request → thread pool to'lishi, lekin consumer thread band bo'lmasligi
3. **Pool rejection:** Pool to'lganda so'rov Kafka'ga qaytishi, consumer ishlashda davom etishi
4. **Message expiry:** 5 minutdan oshiq message → FAILED bo'lishi
5. **Normal flow:** Oddiy so'rovlar avvalgidek ishlashi (regression yo'q)
