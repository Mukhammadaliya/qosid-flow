# Proxy Flow Separation Design

**Date:** 2026-04-14
**Status:** Approved
**Scope:** Split Oracle and API proxy flows into independent topics, consumers, and thread pools

## Problem Statement

Oracle va API proxy flow'lari bitta `gateway.request.new` topic, bitta `RequestConsumer`, va bitta thread pool orqali ishlaydi. Bu coupling productionda cascading failure'ga olib keldi — bitta tashqi API muammosi butun pipeline'ni to'xtatdi.

## Approach

**To'liq split (Approach A):** Har bir flow uchun alohida Kafka topic'lar, consumer class'lar, va HTTP thread pool'lar. Bitta flow buzilsa ikkinchisiga ta'sir qilmaydi.

## Design

### 1. Topic Struktura

Eski 3 ta shared topic o'rniga 6 ta alohida topic:

| Eski topic | Oracle topic | API topic |
|------------|-------------|-----------|
| `gateway.request.new` | `gateway.oracle.request` | `gateway.api.request` |
| `gateway.request.response` | `gateway.oracle.response` | `gateway.api.response` |
| `gateway.request.dlq` | `gateway.oracle.dlq` | `gateway.api.dlq` |

**Config (application.yml):**

```yaml
gateway:
  kafka:
    topics:
      oracle-request: ${KAFKA_TOPIC_ORACLE_REQUEST:gateway.oracle.request}
      oracle-response: ${KAFKA_TOPIC_ORACLE_RESPONSE:gateway.oracle.response}
      oracle-dlq: ${KAFKA_TOPIC_ORACLE_DLQ:gateway.oracle.dlq}
      api-request: ${KAFKA_TOPIC_API_REQUEST:gateway.api.request}
      api-response: ${KAFKA_TOPIC_API_RESPONSE:gateway.api.response}
      api-dlq: ${KAFKA_TOPIC_API_DLQ:gateway.api.dlq}
```

### 2. Consumer va Producer Classlar

**Yangi consumer'lar:**

| Package | Class | Topic | Vazifasi |
|---------|-------|-------|----------|
| `kafka/consumer/oracle/` | `OracleRequestConsumer` | `gateway.oracle.request` | Oracle requestlarni HTTP ga yuborish |
| `kafka/consumer/oracle/` | `OracleResponseConsumer` | `gateway.oracle.response` | Javobni Biruni'ga saqlash + RocksDB |
| `kafka/consumer/oracle/` | `OracleDlqConsumer` | `gateway.oracle.dlq` | Oracle DLQ → Telegram alert |
| `kafka/consumer/api/` | `ApiRequestConsumer` | `gateway.api.request` | API requestlarni HTTP ga yuborish |
| `kafka/consumer/api/` | `ApiResponseConsumer` | `gateway.api.response` | Javobni RocksDB + callback |
| `kafka/consumer/api/` | `ApiDlqConsumer` | `gateway.api.dlq` | API DLQ → Telegram alert |

**Muhim farqlar:**
- `ApiResponseConsumer` — faqat RocksDB + Redis + callback. Oracle save YO'Q.
- `OracleResponseConsumer` — Biruni save + RocksDB + Redis. Callback yo'q (Oracle flow'da X-Callback-Url ishlatilmaydi).

**RequestProducer yangi methodlar:**

```java
// Request
sendOracleRequest(RequestMessage msg)  → gateway.oracle.request
sendApiRequest(RequestMessage msg)     → gateway.api.request

// Response
sendOracleResponse(ResponseMessage msg) → gateway.oracle.response
sendApiResponse(ResponseMessage msg)    → gateway.api.response

// DLQ
sendToOracleDlq(DlqMessage msg)  → gateway.oracle.dlq
sendToApiDlq(DlqMessage msg)     → gateway.api.dlq
```

Eski `sendRequest()`, `sendResponse()`, `sendToDlq()` methodlar o'chiriladi.

**O'chiriladigan fayllar:**
- `kafka/consumer/RequestConsumer.java` → logikasi `OracleRequestConsumer` + `ApiRequestConsumer` ga ko'chadi
- `kafka/consumer/ResponseConsumer.java` → logikasi `OracleResponseConsumer` + `ApiResponseConsumer` ga ko'chadi
- `kafka/consumer/DlqConsumer.java` → logikasi `OracleDlqConsumer` + `ApiDlqConsumer` ga ko'chadi

### 3. KafkaConfig va Thread Pool

**Consumer factory'lar (5 ta):**

```java
@Bean("oracleRequestConsumerFactory")   // concurrency: oracle-min
@Bean("oracleResponseConsumerFactory")  // concurrency: oracle-min
@Bean("apiRequestConsumerFactory")      // concurrency: api-min
@Bean("apiResponseConsumerFactory")     // concurrency: api-min
@Bean("dlqConsumerFactory")             // concurrency: 1
```

**Thread pool'lar (2 ta):**

```java
@Bean("oracleHttpExecutor")  // Oracle HTTP requests uchun
@Bean("apiHttpExecutor")     // API HTTP requests uchun
```

**Concurrency config:**

```yaml
gateway:
  concurrency:
    oracle-min: ${ORACLE_CONCURRENCY_MIN:5}
    oracle-max: ${ORACLE_CONCURRENCY_MAX:10}
    api-min: ${API_CONCURRENCY_MIN:5}
    api-max: ${API_CONCURRENCY_MAX:10}
    thread-pool-multiplier: ${THREAD_POOL_MULTIPLIER:2}
    thread-pool-queue-capacity: ${THREAD_POOL_QUEUE:50}
```

### 4. ProxyController va RequestPuller

**ProxyController:**
- `requestProducer.sendRequest(message)` → `requestProducer.sendApiRequest(message)`
- Boshqa o'zgarmaydi

**RequestPuller:**
- `requestProducer.sendRequest(request)` → `requestProducer.sendOracleRequest(request)`
- Boshqa o'zgarmaydi

## Tegadigan fayllar

| Fayl | O'zgartirish |
|------|-------------|
| `kafka/producer/RequestProducer.java` | Source-specific methodlar |
| `config/KafkaConfig.java` | 6 ta topic bean, 5 ta consumer factory |
| `config/KafkaProperties.java` | 6 ta topic nomi |
| `config/ThreadPoolConfig.java` | 2 ta alohida executor |
| `config/ConcurrencyProperties.java` | oracle-min/max, api-min/max |
| `api/ProxyController.java` | `sendApiRequest()` chaqirish |
| `oracle/RequestPuller.java` | `sendOracleRequest()` chaqirish |
| `application.yml` | Yangi topic'lar, concurrency config |

## Yangi fayllar

| Fayl | Vazifasi |
|------|----------|
| `kafka/consumer/oracle/OracleRequestConsumer.java` | Oracle request → HTTP |
| `kafka/consumer/oracle/OracleResponseConsumer.java` | Response → Biruni save + RocksDB |
| `kafka/consumer/oracle/OracleDlqConsumer.java` | Oracle DLQ → Telegram |
| `kafka/consumer/api/ApiRequestConsumer.java` | API request → HTTP |
| `kafka/consumer/api/ApiResponseConsumer.java` | Response → RocksDB + callback |
| `kafka/consumer/api/ApiDlqConsumer.java` | API DLQ → Telegram |

## O'chiriladigan fayllar

| Fayl | Sabab |
|------|-------|
| `kafka/consumer/RequestConsumer.java` | Oracle + Api consumer'larga ajratildi |
| `kafka/consumer/ResponseConsumer.java` | Oracle + Api response consumer'larga ajratildi |
| `kafka/consumer/DlqConsumer.java` | Oracle + Api DLQ consumer'larga ajratildi |

## Tegmaydigan fayllar (xavfsizlik)

| Fayl | Sabab |
|------|-------|
| `HttpRequestService.java` | CB logic o'zgarmaydi |
| `RequestStateService.java` | Redis state o'zgarmaydi |
| `storage/` (RocksDB) | Storage layer o'zgarmaydi |
| `callback/` | Callback logic o'zgarmaydi |
| `auth/` | Authorization o'zgarmaydi |
| `file/` | File upload o'zgarmaydi |

## Cascading failure himoyasi

Har bir flow mustaqil bo'lganligi uchun:
- Oracle API 503 → faqat `oracleHttpExecutor` band bo'ladi, `apiHttpExecutor` ishlaydi
- API CB OPEN → faqat API consumer'ga ta'sir, Oracle flow ishlaydi
- Pool exhaustion → faqat bitta flow'ning pool'i to'ladi, ikkinchisi mustaqil

Oldingi cascading failure fixlari (CB-aware retry, .subscribe(), RejectWithLogging, message expiry) har ikkala consumer'da saqlanadi.
