# Adaptive Circuit-Breaker Backpressure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a 30-second circuit-breaker window from dumping hundreds of live production requests into the DLQ, without reintroducing the unbounded requeue churn that filled a 57 GB Kafka volume in May.

**Architecture:** Three independent phases. **Phase 1** replaces the count-based `cb-max-requeues` cap with a *time*-based one: a CB-blocked message is re-queued by a scheduler once the circuit is likely to allow traffic again, and gives up only when `max-message-age-ms` (5 min) is exceeded. The Kafka offset is not acknowledged until the delayed requeue is actually produced, so a crash means redelivery, never loss. **Phase 2** stops our own burst from opening the circuit in the first place with a per-host concurrency limit, using the same backpressure path when the limit is hit. **Phase 3** coalesces DLQ alerts so one incident is one Telegram message instead of 212, and honours Telegram's `retry_after`.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Kafka 3.3.16, resilience4j 2.2.0 (circuitbreaker only — `resilience4j-reactor` is **not** on the classpath, so no reactive operators), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- **SAFETY-RULES 23 stays intact:** a CB-blocked response must never increment `attemptCount` and `CIRCUIT_BREAKER` must never be added to `isNonRetryableSource`.
- **SAFETY-RULES 39 stays intact:** every acknowledgement in the two request consumers goes through `OrderedAckTracker`; a record is completed exactly once, and only when its work is finished.
- **SAFETY-RULES 5 stays intact:** Oracle and API consumer logic is never merged. Every change below is applied *twice*, once per flow, with its own instance of any new collaborator.
- **SAFETY-RULES 8 stays intact:** Redis-unavailable paths keep failing closed (`Integer.MAX_VALUE`).
- The Oracle and API flows must stay isolated from each other: one upstream host being down must not stall requests to a healthy host.
- No new Kafka topics, no new infrastructure, no new Maven dependencies.
- Defaults must reproduce today's *intent* (bounded retry) while removing today's *defect* (giving up after ~1 second).
- All new config keys get an entry in `ai-context/CONFIGURATION.md` and an env-var override in `application.yml`.

---

## Incident that motivated this plan

2026-07-27 14:27, production. Sequence, reconstructed from the DLQ topic and the journal:

1. A deploy restart at 14:21 left a pull backlog; the gateway then pulled **1000 requests in 15 seconds**.
2. Those requests were dispatched at that rate to `https://smartup.online/b/trade/api/sm24:order_export`. At least 5 of any 10 calls failed, so resilience4j opened `cb-smartup.online` (`sliding-window-size: 10`, `failure-rate-threshold: 50`).
3. `wait-duration-in-open-state` is **30s**. A CB-blocked message is re-queued to Kafka immediately and comes back in ~20 ms, so `cb-max-requeues: 50` was exhausted in **about one second**.
4. **212 production requests** (tenant 100) plus 20 test-tenant requests were written to the DLQ with `failureReason: "Circuit breaker still open after 51 requeues"`, `httpStatus: 503`, `errorSource: SYSTEM`, `attemptCount: 0` — the upstream never saw them.
5. Each DLQ record produced a Telegram alert. Telegram rate-limited us: 14 sent, **298 failures** with `retry after N`.

The count cap was added on 2026-07-25 to stop an unbounded requeue loop (the 2026-05-18 incident where the same loop filled a 57 GB Redpanda volume and hung the gateway for 34 hours). It fixed that, but a count of round-trips is a poor proxy for elapsed time: the round-trip rate depends on load, not on the circuit. 50 round trips is ~1 s under load and could be ~50 s when idle. The fix is to bound by time and to wait in a way that costs nothing.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/uz/greenwhite/gateway/kafka/retry/CbRequeueScheduler.java` | **New.** Holds a CB-blocked message until the circuit is likely to accept traffic, then produces the requeue and reports completion. Bounded by count and by bytes. One instance per flow. |
| `app/src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java` | Adds `circuitBreakerName(RequestMessage)` and `isCircuitOpen(String cbName)` so callers can ask about circuit state without making a call; adds the per-host dispatch permit (Phase 2). |
| `app/src/main/java/uz/greenwhite/gateway/http/HostConcurrencyLimiter.java` | **New (Phase 2).** Per-host semaphore that caps concurrent in-flight upstream calls; refusals become the same backpressure path as a CB-open. |
| `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java` | CB-blocked branch: schedule a delayed requeue instead of an immediate one; give up on message age. |
| `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java` | Same change, own scheduler instance (rule 5). |
| `app/src/main/java/uz/greenwhite/gateway/config/RetryProperties.java` | New keys: `cb-requeue-delay-ms`, `cb-requeue-max-delay-ms`, `cb-max-pending`, `cb-max-pending-bytes`; `cb-max-requeues` default raised and re-documented as a runaway guard. |
| `app/src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java` | New meters: `gateway.http.circuitbreaker.requeue.delayed`, `...requeue.pending` (gauge), `...requeue.rejected`, `gateway.http.host.limiter.rejected`, `gateway.notification.alert.coalesced`. |
| `app/src/main/java/uz/greenwhite/gateway/notification/TelegramNotificationService.java` | Coalesce DLQ alerts per (reason-class, host) in a window; honour `retry_after`. |
| `app/src/main/resources/application.yml` | New config with env overrides. |
| `ai-context/SAFETY-RULES.md` | Rule 23 rewritten (time-bounded, not count-bounded); new rule 40 for the scheduler's ack contract. |
| `ai-context/CONFIGURATION.md`, `ai-context/ARCHITECTURE.md`, `.env.example` | Document the new keys and the new E3 backpressure behaviour. |
| `docs/manual-test-plan-v1.14.md` | New section K with the prod-observable checks. |

Phases are independently deployable: Phase 1 alone fixes the incident, Phase 2 reduces how often the circuit opens at all, Phase 3 fixes the alert flood.

---

## Immediate mitigation (do this first, before any code)

This is a one-line production config change that buys time until Phase 1 ships. It trades churn for correctness deliberately, and the 5-minute age net bounds it.

- [ ] **Step 1: raise the cap in the production `.env`**

```bash
ssh -i ~/.ssh/smartup24_prod root@77.42.21.222
grep -n RETRY_CB_MAX_REQUEUES /opt/services/qosid-flow/.env || echo "RETRY_CB_MAX_REQUEUES=1500" >> /opt/services/qosid-flow/.env
sed -i 's/^RETRY_CB_MAX_REQUEUES=.*/RETRY_CB_MAX_REQUEUES=1500/' /opt/services/qosid-flow/.env
grep -n RETRY_CB_MAX_REQUEUES /opt/services/qosid-flow/.env
systemctl restart gateway-service
```

1500 round trips at ~20 ms spans ~30 s, i.e. exactly one `wait-duration-in-open-state` window. Expected cost while this is in place: roughly `pending_messages × 1500` extra Kafka round trips per CB window (for the 14:27 burst that is ~300 × 1500 ≈ 450 k records ≈ 900 MB of writes). Watch the Redpanda volume:

```bash
df -h /var/lib/docker; docker exec redpanda rpk cluster info | head -5
```

- [ ] **Step 2: confirm the change took effect**

```bash
journalctl -u gateway-service --since "2 min ago" | grep -c "CB requeue cap exceeded"   # expect 0 on the next CB window
```

Remove this override once Phase 1 is deployed — leaving both in place is harmless but the count cap should go back to being a guard, not a policy.

---

## Phase 1 — Adaptive delayed requeue

### Task 1: Expose circuit state to callers

`HttpRequestService` already derives a CB name per base URL (`cb-<host>`, `HttpRequestService.java:78-88`) but keeps it private to the send path. The consumers need to ask "is this host's circuit open, and for how long?" without making a call.

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java`
- Test: `app/src/test/java/uz/greenwhite/gateway/http/CircuitStateQueryTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `public String circuitBreakerName(RequestMessage request)` — the same `cb-<host>` string the send path uses.
  - `public boolean isCircuitOpen(String cbName)` — `true` only for `CircuitBreaker.State.OPEN` and `FORCED_OPEN`; `false` for `HALF_OPEN` (a probe is allowed) and for an unknown name.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.http;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.model.kafka.RequestMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumers decide how long to wait out a CB window, so they must be able to read the
 * circuit's state without making a call. HALF_OPEN deliberately reads as "not open": the
 * circuit is allowing probe calls, so the message should go straight through.
 */
class CircuitStateQueryTest {

    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

    private HttpRequestService serviceWith(CircuitBreakerRegistry registry) {
        return HttpRequestServiceTestFixtures.withRegistry(registry);
    }

    @Test
    void circuitBreakerNameIsDerivedFromTheHost() {
        HttpRequestService service = serviceWith(registry);
        RequestMessage request = RequestMessage.builder()
                .tenantId(100L).requestId(1L)
                .url("POST https://smartup.online/b/trade/api/sm24:order_export").build();

        assertThat(service.circuitBreakerName(request)).isEqualTo("cb-smartup.online");
    }

    @Test
    void openCircuitReadsAsOpen() {
        CircuitBreaker cb = registry.circuitBreaker("cb-smartup.online");
        cb.transitionToOpenState();

        assertThat(serviceWith(registry).isCircuitOpen("cb-smartup.online")).isTrue();
    }

    @Test
    void halfOpenCircuitReadsAsNotOpenBecauseProbesAreAllowed() {
        CircuitBreaker cb = registry.circuitBreaker("cb-smartup.online");
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();

        assertThat(serviceWith(registry).isCircuitOpen("cb-smartup.online")).isFalse();
    }

    @Test
    void unknownCircuitReadsAsNotOpen() {
        assertThat(serviceWith(registry).isCircuitOpen("cb-never-seen")).isFalse();
    }
}
```

`HttpRequestServiceTestFixtures.withRegistry(...)` does not exist yet — create it in the same package as a package-private helper that builds `HttpRequestService` with mocks for everything except the registry. Read the existing `HttpRequestServiceTest` constructor call first and mirror it exactly; do not invent constructor parameters.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -P '!frontend' -pl app -am -Dtest=CircuitStateQueryTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compile error — `circuitBreakerName`/`isCircuitOpen` do not exist.

- [ ] **Step 3: Implement**

In `HttpRequestService`, promote the existing private CB-name helper and add the state query. Keep the existing private method as the single source of the name so the send path and the query can never disagree:

```java
    /** The circuit this request's host is protected by — same value the send path uses. */
    public String circuitBreakerName(RequestMessage request) {
        return resolveCircuitBreakerName(request);   // existing private helper, renamed if needed
    }

    /**
     * True only while the circuit refuses calls outright. HALF_OPEN is deliberately NOT open:
     * resilience4j permits a limited number of probe calls in that state, and a waiting message
     * should be one of them rather than sleeping through the recovery window.
     */
    public boolean isCircuitOpen(String cbName) {
        CircuitBreaker.State state = circuitBreakerRegistry.circuitBreaker(cbName).getState();
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }
```

If `HttpRequestService` currently obtains circuit breakers through Spring Cloud's `CircuitBreakerFactory` rather than a resilience4j `CircuitBreakerRegistry`, inject `CircuitBreakerRegistry` additionally — it is a bean provided by `resilience4j-spring-boot3` — and do **not** change how the send path acquires permission (rule 5 protected zone: `HttpRequestService` is listed in Protected Zones).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -P '!frontend' -pl app -am -Dtest=CircuitStateQueryTest,HttpRequestServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS, and `HttpRequestServiceTest` still green (the send path is untouched).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java \
        app/src/test/java/uz/greenwhite/gateway/http/CircuitStateQueryTest.java \
        app/src/test/java/uz/greenwhite/gateway/http/HttpRequestServiceTestFixtures.java
git commit -m "feat(http): circuit state is readable without making a call

Konsumerlar CB oynasini kutish qarorini qabul qilishi uchun holatni chaqiruvsiz
o'qishi kerak. HALF_OPEN ataylab 'ochiq emas' deb hisoblanadi — o'sha holatda
resilience4j sinov chaqiruvlariga ruxsat beradi va kutayotgan xabar aynan
shulardan biri bo'lishi kerak."
```

---

### Task 2: The delayed requeue scheduler

**Files:**
- Create: `app/src/main/java/uz/greenwhite/gateway/kafka/retry/CbRequeueScheduler.java`
- Test: `app/src/test/java/uz/greenwhite/gateway/kafka/retry/CbRequeueSchedulerTest.java`

**Interfaces:**
- Consumes: `HttpRequestService.isCircuitOpen(String)` (Task 1).
- Produces:
  - `CbRequeueScheduler(String flowLabel, ScheduledExecutorService scheduler, RetryProperties retryProperties, GatewayMetrics metrics, Predicate<String> circuitOpen)`
  - `boolean schedule(String key, String cbName, long bodyBytes, Runnable requeueAndComplete)` — returns `false` when the pending bound is exceeded (caller must then DLQ); `true` when accepted.
  - `int pendingCount()` / `long pendingBytes()` — for the gauge and for tests.
  - `void shutdown()` — `@PreDestroy` hook; drains by running every pending requeue immediately so a graceful shutdown does not sit on messages.

**Design contract, stated once so the implementer does not have to infer it:**

1. `schedule(...)` never blocks the caller.
2. The `requeueAndComplete` runnable is the *only* place the Kafka record is completed. It must produce the requeue **and then** call `ackTracker.complete(...)`. Until it runs, the record's offset stays uncommitted, so a crash redelivers the message instead of losing it (rule 39). Commits for that partition stall for the wait duration — that is intended and shows up as consumer lag, which is the correct signal.
3. Delay: `cb-requeue-delay-ms` (default 2000) on the first wait, doubling on each re-check up to `cb-requeue-max-delay-ms` (default 30000, i.e. one CB window). Re-check means: when the timer fires, ask `circuitOpen`; if still open, reschedule with the next delay; if not open, run the runnable.
4. Bounds: refuse (`return false`) when `pendingCount() >= cb-max-pending` (default 5000) **or** `pendingBytes() + bodyBytes > cb-max-pending-bytes` (default 268435456 = 256 MiB). The byte bound matters because a REST-sourced body can be up to 20 MiB (`gateway.proxy.max-body-size`), so 5000 held messages are not necessarily small.
5. Jitter: add `ThreadLocalRandom.current().nextLong(250)` ms to each delay so a burst does not stampede the upstream in lockstep when the circuit closes.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.kafka.retry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A CB-blocked message must wait out the circuit window instead of spinning through Kafka.
 * Before this class, a 30-second window cost ~1500 round trips per message and the count cap
 * (50) turned 212 live production requests into DLQ records in about one second.
 */
class CbRequeueSchedulerTest {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private RetryProperties props(int maxPending, long maxPendingBytes) {
        RetryProperties p = new RetryProperties();
        p.setCbRequeueDelayMs(30);        // short delays keep the test fast
        p.setCbRequeueMaxDelayMs(60);
        p.setCbMaxPending(maxPending);
        p.setCbMaxPendingBytes(maxPendingBytes);
        return p;
    }

    private GatewayMetrics metrics() {
        GatewayMetrics m = mock(GatewayMetrics.class);
        Counter c = mock(Counter.class);
        when(m.getCbRequeueDelayed()).thenReturn(c);
        when(m.getCbRequeueRejected()).thenReturn(c);
        when(m.getRegistry()).thenReturn(new SimpleMeterRegistry());
        return m;
    }

    @Test
    void doesNotRequeueWhileTheCircuitIsStillOpen() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean requeued = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> open.get());

        assertThat(scheduler.schedule("1:1", "cb-x", 100, () -> requeued.set(true))).isTrue();

        Thread.sleep(200);
        assertThat(requeued).as("circuit still open — the message must keep waiting").isFalse();
        assertThat(scheduler.pendingCount()).isEqualTo(1);
    }

    @Test
    void requeuesAsSoonAsTheCircuitCloses() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicBoolean requeued = new AtomicBoolean(false);
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> open.get());
        scheduler.schedule("1:1", "cb-x", 100, () -> requeued.set(true));

        open.set(false);

        long deadline = System.currentTimeMillis() + 2000;
        while (!requeued.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(requeued).isTrue();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(scheduler.pendingBytes()).isZero();
    }

    @Test
    void refusesWhenTheCountBoundIsReached() {
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(2, 1_000_000), metrics(), cb -> true);

        assertThat(scheduler.schedule("1:1", "cb-x", 10, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:2", "cb-x", 10, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:3", "cb-x", 10, () -> {}))
                .as("beyond the bound the caller must DLQ rather than the scheduler growing")
                .isFalse();
    }

    /** A single 20 MiB REST body must not be able to pin memory just because the count is low. */
    @Test
    void refusesWhenTheByteBoundIsReached() {
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(1000, 1000), metrics(), cb -> true);

        assertThat(scheduler.schedule("1:1", "cb-x", 900, () -> {})).isTrue();
        assertThat(scheduler.schedule("1:2", "cb-x", 200, () -> {})).isFalse();
    }

    /** Graceful shutdown must not sit on messages whose offsets are uncommitted. */
    @Test
    void shutdownDrainsPendingRequeuesImmediately() {
        List<String> done = new CopyOnWriteArrayList<>();
        CbRequeueScheduler scheduler = new CbRequeueScheduler(
                "TEST", executor, props(100, 1_000_000), metrics(), cb -> true);
        scheduler.schedule("1:1", "cb-x", 10, () -> done.add("1:1"));
        scheduler.schedule("1:2", "cb-x", 10, () -> done.add("1:2"));

        scheduler.shutdown();

        assertThat(done).containsExactlyInAnyOrder("1:1", "1:2");
        assertThat(scheduler.pendingCount()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -P '!frontend' -pl app -am -Dtest=CbRequeueSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compile error — `CbRequeueScheduler` and the four new `RetryProperties` setters do not exist.

- [ ] **Step 3: Add the config keys**

In `RetryProperties` (Lombok `@Data`, so setters are generated):

```java
    /**
     * First wait before re-checking a blocked circuit, in ms. Doubles on each re-check up to
     * {@link #cbRequeueMaxDelayMs}. This is what replaced the old spin-requeue: at Kafka
     * round-trip speed (~20 ms) a 30 s circuit window cost ~1500 pointless round trips per
     * message, and the count cap turned that into false DLQ records after ~1 s.
     */
    private long cbRequeueDelayMs = 2_000;

    /**
     * Upper bound for the doubling wait, in ms. Defaults to one
     * {@code resilience4j.circuitbreaker.*.wait-duration-in-open-state} (30 s) — waiting longer
     * than the window cannot help, and {@link #maxMessageAgeMs} is what ultimately gives up.
     */
    private long cbRequeueMaxDelayMs = 30_000;

    /** Max messages held in memory waiting for a circuit, per flow. Beyond this: DLQ. */
    private int cbMaxPending = 5_000;

    /**
     * Max total held body bytes per flow. A REST body can be 20 MiB
     * ({@code gateway.proxy.max-body-size}), so a count bound alone does not bound memory.
     */
    private long cbMaxPendingBytes = 268_435_456;   // 256 MiB
```

Change the meaning of the existing key, keeping the field name so no config breaks:

```java
    /**
     * Runaway guard, NOT a retry policy. Since the requeue is time-delayed (see
     * {@link #cbRequeueDelayMs}) a message needs at most
     * {@code maxMessageAgeMs / cbRequeueDelayMs} requeues (~150 at the defaults) to wait out any
     * realistic outage, so this only fires if the delay logic itself breaks. It was 50 until
     * 2026-07-27, when a 30 s circuit window DLQ'd 212 live production requests in one second —
     * see SAFETY-RULES 23.
     */
    private int cbMaxRequeues = 1_000;
```

- [ ] **Step 4: Add the metrics**

In `GatewayMetrics`, next to the existing `cbRequeueExhausted` (line ~198):

```java
    private final Counter cbRequeueDelayed;
    private final Counter cbRequeueRejected;
```

```java
        this.cbRequeueDelayed = Counter.builder("gateway.http.circuitbreaker.requeue.delayed")
                .description("CB-blocked messages parked until the circuit accepts traffic again")
                .register(registry);
        this.cbRequeueRejected = Counter.builder("gateway.http.circuitbreaker.requeue.rejected")
                .description("CB-blocked messages sent to DLQ because the pending bound was full")
                .register(registry);
```

Do **not** remove `cbRequeueExhausted` — SAFETY-RULES 3 forbids removing metrics, and the Prometheus rules reference it.

- [ ] **Step 5: Implement the scheduler**

```java
package uz.greenwhite.gateway.kafka.retry;

import lombok.extern.slf4j.Slf4j;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Parks a circuit-breaker-blocked message until its circuit will accept traffic again, then
 * hands it back to the caller to be re-queued.
 *
 * <p><b>Why this exists.</b> The CB-blocked branch used to re-queue immediately, so a message
 * bounced through Kafka every ~20 ms for as long as the circuit stayed open (30 s by
 * configuration). The count cap that bounded that loop (`cb-max-requeues`, then 50) was
 * therefore spent in about one second: on 2026-07-27 a single circuit window turned 212 live
 * production requests into DLQ records that the upstream never saw. Waiting is free; spinning
 * is not.
 *
 * <p><b>Ack contract (SAFETY-RULES 39, 40).</b> The {@code requeueAndComplete} runnable is the
 * only place the Kafka record is completed, and it runs only after the requeue is produced.
 * Until then the offset stays uncommitted, so a crash redelivers the message rather than losing
 * it — and the idempotency gate absorbs the duplicate. Commits for that partition stall for the
 * wait; that shows up as consumer lag, which is the honest signal for "we are waiting on an
 * upstream".
 *
 * <p><b>Bounded.</b> Held messages cost memory, so the pending set is bounded by both count and
 * bytes; past either bound {@link #schedule} returns {@code false} and the caller must fall back
 * to DLQ. One instance per flow — Oracle and API never share this state (SAFETY-RULES 5).
 */
@Slf4j
public class CbRequeueScheduler {

    private final String flowLabel;
    private final ScheduledExecutorService scheduler;
    private final RetryProperties retryProperties;
    private final GatewayMetrics metrics;
    private final Predicate<String> circuitOpen;

    /** A parked message: what it costs in memory, and how to hand it back when the wait is over. */
    private record Parked(long bytes, Runnable requeueAndComplete) {}

    private final Map<String, Parked> pending = new ConcurrentHashMap<>();
    private final AtomicLong pendingBytes = new AtomicLong();

    public CbRequeueScheduler(String flowLabel,
                              ScheduledExecutorService scheduler,
                              RetryProperties retryProperties,
                              GatewayMetrics metrics,
                              Predicate<String> circuitOpen) {
        this.flowLabel = flowLabel;
        this.scheduler = scheduler;
        this.retryProperties = retryProperties;
        this.metrics = metrics;
        this.circuitOpen = circuitOpen;
        metrics.getRegistry().gauge("gateway.http.circuitbreaker.requeue.pending",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("flow", flowLabel)),
                this, CbRequeueScheduler::pendingCount);
    }

    /**
     * @param bodyBytes         held payload size, for the byte bound
     * @param requeueAndComplete produce the requeue AND complete the Kafka record, in that order
     * @return false when the pending bound is full — the caller must DLQ the message
     */
    public boolean schedule(String key, String cbName, long bodyBytes, Runnable requeueAndComplete) {
        if (pending.size() >= retryProperties.getCbMaxPending()
                || pendingBytes.get() + bodyBytes > retryProperties.getCbMaxPendingBytes()) {
            metrics.getCbRequeueRejected().increment();
            log.error("[{}] CB requeue pending bound full ({} msgs / {} bytes) — DLQ: {}",
                    flowLabel, pending.size(), pendingBytes.get(), key);
            return false;
        }
        pending.put(key, new Parked(bodyBytes, requeueAndComplete));
        pendingBytes.addAndGet(bodyBytes);
        metrics.getCbRequeueDelayed().increment();
        arm(key, cbName, bodyBytes, requeueAndComplete, retryProperties.getCbRequeueDelayMs());
        return true;
    }

    private void arm(String key, String cbName, long bodyBytes, Runnable requeueAndComplete, long delayMs) {
        long jittered = delayMs + ThreadLocalRandom.current().nextLong(250);
        scheduler.schedule(() -> {
            try {
                if (circuitOpen.test(cbName)) {
                    long next = Math.min(delayMs * 2, retryProperties.getCbRequeueMaxDelayMs());
                    log.debug("[{}] circuit {} still open, re-checking in {}ms: {}",
                            flowLabel, cbName, next, key);
                    arm(key, cbName, bodyBytes, requeueAndComplete, next);
                    return;
                }
                release(key, bodyBytes);
                requeueAndComplete.run();
            } catch (Exception e) {
                // Never swallow silently: this thread owns an uncommitted Kafka offset.
                release(key, bodyBytes);
                log.error("[{}] delayed requeue failed for {} — the record stays uncommitted and "
                        + "will be redelivered: {}", flowLabel, key, e.getMessage(), e);
            }
        }, jittered, TimeUnit.MILLISECONDS);
    }

    private void release(String key, long bodyBytes) {
        if (pending.remove(key) != null) {
            pendingBytes.addAndGet(-bodyBytes);
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    public long pendingBytes() {
        return pendingBytes.get();
    }

    /**
     * Runs every parked requeue immediately. Called on shutdown: a parked message holds an
     * uncommitted offset, and producing it now turns a would-be redelivery into a clean handover.
     * Failures are logged and swallowed — the offset is still uncommitted, so the worst case is
     * redelivery, and throwing here would abandon the remaining parked messages.
     */
    public void shutdown() {
        log.info("[{}] draining {} parked CB requeues on shutdown", flowLabel, pending.size());
        for (String key : pending.keySet().toArray(String[]::new)) {
            Parked parked = pending.remove(key);
            if (parked == null) {
                continue;   // its timer fired first
            }
            pendingBytes.addAndGet(-parked.bytes());
            try {
                parked.requeueAndComplete().run();
            } catch (Exception e) {
                log.error("[{}] drain failed for {} — will be redelivered: {}",
                        flowLabel, key, e.getMessage());
            }
        }
    }
}
```

`release(key, bytes)` becomes:

```java
    private void release(String key, long bodyBytes) {
        if (pending.remove(key) != null) {
            pendingBytes.addAndGet(-bodyBytes);
        }
    }
```

Both `release` and `shutdown` remove from the map before touching the runnable, so a message can never be requeued twice (its timer firing concurrently with a shutdown finds the entry already gone).

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -P '!frontend' -pl app -am -Dtest=CbRequeueSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS (5 tests).

- [ ] **Step 7: Mutation-check the guard**

Temporarily make `arm(...)` ignore `circuitOpen` and always run the runnable. Re-run: `doesNotRequeueWhileTheCircuitIsStillOpen` must fail. Revert. (This is the check that caught a toothless test suite on 2026-07-26 — do not skip it.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/kafka/retry/CbRequeueScheduler.java \
        app/src/main/java/uz/greenwhite/gateway/config/RetryProperties.java \
        app/src/main/java/uz/greenwhite/gateway/metrics/GatewayMetrics.java \
        app/src/test/java/uz/greenwhite/gateway/kafka/retry/CbRequeueSchedulerTest.java
git commit -m "feat(retry): CB-blocked xabarlar Kafka'da aylanmasdan kutadi

Chegara endi VAQT bo'yicha: xabar CB holatini 2s dan boshlab, 30s gacha ikkilanib
qayta tekshiradi va faqat CB ochiq bo'lmaganda qayta navbatga tushadi. Ilgari u
~20ms da qaytib, 50 talik hisob chegarasini ~1 soniyada tugatardi — 2026-07-27 da
shu sababdan 212 ta jonli prod so'rovi DLQ'ga tushgan.

Ack shartnomasi: yozuv faqat requeue amalga oshgandan keyin complete qilinadi,
ya'ni crash yo'qotish emas, qayta yetkazish beradi (39-qoida). Kutish partisiya
commit'ini to'xtatadi — bu lag sifatida ko'rinadi va aynan to'g'ri signal.

Pending to'plam soni VA baytlari bilan chegaralangan (REST tanasi 20 MiB gacha
bo'lishi mumkin); chegaradan oshsa DLQ."
```

---

### Task 3: Wire the scheduler into the API consumer

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java:246-268`
- Modify: `app/src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java` (add the shared scheduler bean)
- Test: `app/src/test/java/uz/greenwhite/gateway/kafka/consumer/CbBackpressureTest.java`

**Interfaces:**
- Consumes: `CbRequeueScheduler.schedule(...)`, `HttpRequestService.circuitBreakerName(...)`, `isCircuitOpen(...)`.
- Produces: nothing new for later tasks.

The new CB branch, replacing lines 246-268. Read the current block first — the surrounding `handleFailedResponse` signature and the `handlePermanentFailure` call shape must stay exactly as they are.

```java
        if ("CIRCUIT_BREAKER".equals(errorSourceStr)) {
            // SAFETY-RULES 23: CB-blocked is transient back-pressure — attemptCount stays
            // untouched and CIRCUIT_BREAKER is never added to isNonRetryableSource.
            //
            // Give-up is by TIME, not by round-trip count: the message is parked until the
            // circuit accepts traffic again, and isMessageExpired (max-message-age-ms, 5 min)
            // is what eventually turns a genuinely stuck message into one DLQ record. The
            // count cap below is only a runaway guard for a broken delay path.
            if (isMessageExpired(message)) {
                long ageMs = Duration.between(message.getCreatedAt(), LocalDateTime.now()).toMillis();
                metrics.getCbRequeueExhausted().increment();
                log.error("[API] CB still open and message expired (age={}ms), sending to DLQ: {}",
                        ageMs, key);
                handlePermanentFailure(key, message, httpStatus,
                        "Circuit breaker still open after " + ageMs + "ms", ErrorSource.SYSTEM);
                return;
            }

            int requeues = requestStateService.incrementCbRequeue(key);
            if (requeues > retryProperties.getCbMaxRequeues()) {
                metrics.getCbRequeueExhausted().increment();
                log.error("[API] CB requeue runaway guard tripped ({} > {}), sending to DLQ: {}",
                        requeues, retryProperties.getCbMaxRequeues(), key);
                handlePermanentFailure(key, message, httpStatus,
                        "Circuit breaker requeue guard tripped after " + requeues + " requeues",
                        ErrorSource.SYSTEM);
                return;
            }

            metrics.getHttpRetry().increment();
            String cbName = httpRequestService.circuitBreakerName(message);
            long bodyBytes = message.getBody() == null ? 0 : message.getBody().length();
            boolean parked = cbRequeueScheduler.schedule(key, cbName, bodyBytes, () -> {
                requestProducer.sendApiRequest(message);
                ackTracker.complete(partition, offset);
            });
            if (parked) {
                // Deliberately NOT completing the record here: the scheduler's runnable owns it.
                log.warn("[API] CB-blocked, parked until {} accepts traffic ({}/{}): {}",
                        cbName, requeues, retryProperties.getCbMaxRequeues(), key);
                return CB_PARKED;
            }
            log.error("[API] CB pending bound full, sending to DLQ: {}", key);
            handlePermanentFailure(key, message, httpStatus,
                    "Circuit breaker open and requeue buffer full", ErrorSource.SYSTEM);
            return;
        }
```

**The one structural problem to solve carefully:** today `handleFailedResponse` returns `void` and the caller's `finally` block calls `ackTracker.complete(partition, offset)` unconditionally (`ApiRequestConsumer.java:193`). When a message is parked, that `finally` must **not** complete the record — the scheduler will. Change `handleFailedResponse` to return a `boolean parked` (or an enum; `boolean` is enough) and make the subscribe callbacks read it:

```java
                        response -> {
                            boolean parked = false;
                            try {
                                httpSample.stop(metrics.getHttpRequestTimer());
                                if (response != null && response.isSuccess()) {
                                    metrics.getHttpSuccess().increment();
                                    handleSuccess(key, message, response);
                                } else {
                                    int httpStatus = response != null ? response.getHttpStatus() : 0;
                                    metrics.recordHttpResult(httpStatus);
                                    parked = handleFailedResponse(key, message, response);
                                }
                            } catch (Exception e) {
                                log.error("[API] Error handling response for {}: {}", key, e.getMessage(), e);
                            } finally {
                                requestStateService.releaseLock(key);
                                // A parked message's record is completed by CbRequeueScheduler once
                                // the requeue is produced — completing it here would commit the
                                // offset while the work is still outstanding (SAFETY-RULES 39/40).
                                if (!parked) {
                                    ackTracker.complete(partition, offset);
                                }
                            }
                        },
```

Note the lock: `releaseLock(key)` still runs immediately, which is correct — the message is not being processed while parked, and the 300 s lock TTL must not be held for the whole wait.

- [ ] **Step 1: Write the failing test**

```java
package uz.greenwhite.gateway.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Mono;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.consumer.api.ApiRequestConsumer;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
import uz.greenwhite.gateway.state.RequestStateService;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A CB-blocked message must be parked, not re-queued at Kafka speed, and its Kafka record must
 * NOT be completed while it is parked — otherwise the offset commits while the requeue is still
 * outstanding, which is the silent-loss shape SAFETY-RULES 39 exists to prevent.
 *
 * <p>On 2026-07-27 the old immediate-requeue path spent its 50-round-trip budget in about one
 * second and wrote 212 live production requests to the DLQ during a single 30-second circuit
 * window. These tests pin the replacement.
 */
class CbBackpressureTest {

    private static final String KEY = "100:15073141";
    private static final String TOPIC = "gateway.request.new";

    private final RequestProducer producer = mock(RequestProducer.class);
    private final RequestStateService state = mock(RequestStateService.class);
    private final HttpRequestService http = mock(HttpRequestService.class);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean circuitOpen = new AtomicBoolean(true);

    /** Runs submitted work inline so the reactive callback executes during consumeRequest(). */
    private ThreadPoolTaskExecutor inlineExecutor() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    private GatewayMetrics metrics() {
        GatewayMetrics m = mock(GatewayMetrics.class);
        Counter c = mock(Counter.class);
        when(m.getConsumerReceived()).thenReturn(c);
        when(m.getHttpRetry()).thenReturn(c);
        when(m.getCbRequeueExhausted()).thenReturn(c);
        when(m.getCbRequeueDelayed()).thenReturn(c);
        when(m.getCbRequeueRejected()).thenReturn(c);
        when(m.getHttpRequestTimer()).thenReturn(mock(Timer.class));
        when(m.getRegistry()).thenReturn(new SimpleMeterRegistry());
        return m;
    }

    private RetryProperties retryProps(int maxPending) {
        RetryProperties p = new RetryProperties();
        p.setCbRequeueDelayMs(20);
        p.setCbRequeueMaxDelayMs(40);
        p.setCbMaxPending(maxPending);
        p.setCbMaxPendingBytes(1_000_000);
        p.setCbMaxRequeues(1000);
        p.setMaxMessageAgeMs(300_000);
        return p;
    }

    /** The synthetic 503 that HttpRequestService.buildCircuitBreakerResponse produces. */
    private ResponseMessage cbBlocked() {
        return ResponseMessage.builder()
                .tenantId(100L).requestId(15073141L)
                .httpStatus(503).errorSource("CIRCUIT_BREAKER")
                .errorMessage("Circuit breaker open").build();
    }

    private ConsumerRecord<String, RequestMessage> record(LocalDateTime createdAt) {
        RequestMessage msg = RequestMessage.builder()
                .tenantId(100L).requestId(15073141L).source("ORACLE")
                .url("POST https://smartup.online/b/trade/api/sm24:order_export")
                .body("{\"modified_id\":\"3035052873\"}")
                .createdAt(createdAt).build();
        return new ConsumerRecord<>(TOPIC, 0, 0L, KEY, msg);
    }

    private ApiRequestConsumer consumer(RetryProperties props) {
        when(state.isCompleted(KEY)).thenReturn(false);
        when(state.tryLock(KEY)).thenReturn(true);
        when(state.getState(anyString())).thenReturn(Optional.empty());
        when(state.incrementCbRequeue(KEY)).thenReturn(1);
        when(http.sendRequest(any())).thenReturn(Mono.just(cbBlocked()));
        when(http.circuitBreakerName(any())).thenReturn("cb-smartup.online");
        when(http.isCircuitOpen("cb-smartup.online")).thenAnswer(inv -> circuitOpen.get());
        when(producer.sendOracleRequest(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(producer.sendApiRequest(any())).thenReturn(CompletableFuture.completedFuture(null));
        return new ApiRequestConsumer(http, state, producer, props, inlineExecutor(),
                metrics(), mock(KafkaProperties.class), mock(OAuth2ProviderService.class), timer);
    }

    @Test
    void cbBlockedResponseIsParkedAndTheRecordStaysUncommitted() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(100)).consumeRequest(record(LocalDateTime.now()), ack);

        verify(ack, never()).acknowledge();
        verify(producer, never()).sendApiRequest(any());
        verify(state).releaseLock(KEY);   // the lock must not be held for the whole wait
    }

    @Test
    void parkedMessageIsRequeuedAndCompletedOnceTheCircuitCloses() {
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer(retryProps(100)).consumeRequest(record(LocalDateTime.now()), ack);

        circuitOpen.set(false);

        verify(producer, timeout(2000)).sendApiRequest(any());
        verify(ack, timeout(2000)).acknowledge();
    }

    @Test
    void expiredMessageGoesToDlqInsteadOfBeingParkedForever() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(100)).consumeRequest(record(LocalDateTime.now().minusMinutes(6)), ack);

        verify(producer).sendToDlq(any());          // exact name: read RequestProducer first
        verify(producer, never()).sendApiRequest(any());
        verify(ack).acknowledge();
    }

    @Test
    void pendingBoundFullFallsBackToDlq() {
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer(retryProps(0)).consumeRequest(record(LocalDateTime.now()), ack);

        verify(producer).sendToDlq(any());
        verify(ack).acknowledge();
    }
}
```

Two details to confirm against the real code before running: the DLQ producer method name (`sendToDlq` above — read `RequestProducer` and use whatever `handlePermanentFailure` actually calls), and the `ApiRequestConsumer` constructor argument order after adding `ScheduledExecutorService`. Everything else is as written.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -P '!frontend' -pl app -am -Dtest=CbBackpressureTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — the consumer currently re-queues immediately and completes the record.

- [ ] **Step 3: Add the scheduler bean**

In `ThreadPoolConfig` (or `KafkaConfig` if that is where the flow beans live — check which file already owns `apiHttpExecutor`):

```java
    /**
     * Shared timer for parked CB requeues. Two threads is plenty: the tasks only re-check a
     * circuit state and hand off to the Kafka producer.
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService cbRequeueTimer() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "cb-requeue-timer");
            t.setDaemon(true);
            return t;
        });
    }
```

- [ ] **Step 4: Implement the consumer change**

Apply the CB branch and the `boolean parked` plumbing shown above. Construct the scheduler as a field, one instance per consumer (rule 5):

```java
    private final CbRequeueScheduler cbRequeueScheduler;
```

and in the constructor (add `ScheduledExecutorService cbRequeueTimer` as a parameter):

```java
        this.cbRequeueScheduler = new CbRequeueScheduler(
                "API", cbRequeueTimer, retryProperties, metrics, httpRequestService::isCircuitOpen);
```

Add `@PreDestroy` on the consumer that calls `cbRequeueScheduler.shutdown()`.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -P '!frontend' -pl app -am -Dtest='CbBackpressureTest,OrderedAckConsumerTest,OrderedAckTrackerTest,LockContentionNackTest,RequestStateRequeueCountTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: all PASS. `RequestStateRequeueCountTest` guards `incrementCbRequeue` and must still be green — the counter is still used, just as a runaway guard.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java \
        app/src/main/java/uz/greenwhite/gateway/config/ThreadPoolConfig.java \
        app/src/test/java/uz/greenwhite/gateway/kafka/consumer/CbBackpressureTest.java
git commit -m "fix(api): CB-blocked so'rov park qilinadi, offset esa commit qilinmaydi"
```

---

### Task 4: The same change in the Oracle consumer

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java`
- Test: extend `app/src/test/java/uz/greenwhite/gateway/kafka/consumer/CbBackpressureTest.java`

Repeat Task 3 exactly, with `[ORACLE]` log prefixes, `requestProducer.sendOracleRequest(message)`, `"ORACLE"` as the flow label, and its **own** `CbRequeueScheduler` instance. The two consumers must not share the scheduler: a circuit stuck open on one flow must not consume the other flow's pending budget (SAFETY-RULES 5).

- [ ] **Step 1: Add the Oracle cases to the test** (mirror the four API cases; `verify(producer).sendOracleRequest(...)`).
- [ ] **Step 2: Run — expect failure.**
- [ ] **Step 3: Apply the same branch + `boolean parked` plumbing.**
- [ ] **Step 4: Run the same test set — expect PASS.**
- [ ] **Step 5: Commit** (`fix(oracle): CB-blocked so'rov park qilinadi (5-qoida: alohida scheduler)`).

---

### Task 5: Config, docs and the full suite

**Files:**
- Modify: `app/src/main/resources/application.yml`, `.env.example`
- Modify: `ai-context/CONFIGURATION.md`, `ai-context/SAFETY-RULES.md`, `ai-context/ARCHITECTURE.md`
- Modify: `docs/manual-test-plan-v1.14.md`

- [ ] **Step 1: `application.yml`** — under `gateway.retry`:

```yaml
    # CB-blocked xabar Kafka'da aylanmasdan kutadi: 2s dan boshlab, har tekshiruvda
    # ikkilanib, 30s (bitta CB oynasi) gacha. Taslim bo'lish VAQT bo'yicha —
    # max-message-age-ms. Batafsil: SAFETY-RULES 23.
    cb-requeue-delay-ms: ${RETRY_CB_REQUEUE_DELAY:2000}
    cb-requeue-max-delay-ms: ${RETRY_CB_REQUEUE_MAX_DELAY:30000}
    # Kutayotgan xabarlar xotirada turadi — soni VA baytlari bilan chegaralangan.
    cb-max-pending: ${RETRY_CB_MAX_PENDING:5000}
    cb-max-pending-bytes: ${RETRY_CB_MAX_PENDING_BYTES:268435456}
```

and change the existing line's comment to say the cap is a runaway guard:

```yaml
    # RUNAWAY GUARD, retry siyosati EMAS (2026-07-27 dan). Kechikishli requeue bilan
    # xabarga ~150 requeue yetadi; bu chegara faqat kechikish mantig'i buzilsa ishlaydi.
    cb-max-requeues: ${RETRY_CB_MAX_REQUEUES:1000}
```

- [ ] **Step 2: `SAFETY-RULES.md` rule 23** — rewrite the second half: the safety net is `max-message-age-ms`, the requeue is delayed and CB-state-driven, `cb-max-requeues` is a runaway guard, and the 2026-07-27 incident is the reason. Add **rule 40**:

> ### 40. Never complete a parked CB record outside `CbRequeueScheduler`
>
> When `handleFailedResponse` parks a CB-blocked message it returns `true` and the caller's
> `finally` block must skip `ackTracker.complete(...)`. The scheduler's runnable produces the
> requeue and completes the record, in that order. Completing it at both places commits the
> offset while the requeue is still outstanding — the silent-loss shape rule 39 exists to
> prevent. Guards: `CbBackpressureTest`, `OrderedAckConsumerTest`.

- [ ] **Step 3: `CONFIGURATION.md`** — a row per new key plus a short section explaining the arithmetic: `wait-duration-in-open-state` (30 s) ÷ `cb-requeue-delay-ms` (2 s) ≈ 15 waits per window, versus ~1500 round trips before.

- [ ] **Step 4: `ARCHITECTURE.md`** — in the E3 section, describe the park-and-recheck path and that consumer lag is the expected signal while a circuit is open.

- [ ] **Step 5: manual test plan, new section K:**

```markdown
## K. Adaptive CB backpressure (v2.3.0)

- [ ] **K1. Park, don't spin** — CB'ni sun'iy ochish uchun `gateway.api.base-url`ni mavjud
      bo'lmagan portga o'zgartiring (ops orqali, gateway o'chiq holda), start qiling, 10+ so'rov
      keling. Kutilgan: jurnalda `parked until cb-... accepts traffic`, DLQ **bo'sh**,
      `gateway_http_circuitbreaker_requeue_pending` gauge > 0, Kafka lag **o'sadi** (bu normal).
      Base-url'ni qaytaring → parked xabarlar o'tadi, lag 0 ga qaytadi, DLQ hali bo'sh.
- [ ] **K2. Vaqt bo'yicha taslim** — `RETRY_MAX_MESSAGE_AGE=60000` qilib, CB'ni 2 daqiqa ochiq
      qoldiring. Kutilgan: ~60s dan keyin DLQ, sabab `Circuit breaker still open after NNNNNms`
      (requeues EMAS), har xabar uchun BITTA DLQ yozuvi.
- [ ] **K3. Pending chegarasi** — `RETRY_CB_MAX_PENDING=5`, CB ochiq, 20 so'rov. Kutilgan:
      5 tasi park, qolgani `Circuit breaker open and requeue buffer full` sababi bilan DLQ.
- [ ] **K4. Restart parked xabarni yo'qotmaydi** — CB ochiq, 10 xabar park bo'lgan holda
      `systemctl restart gateway-service`. Kutilgan: shutdown'da `draining N parked CB requeues`,
      restartdan keyin o'sha 10 id Redis'da COMPLETED bo'ladi (yoki qayta yetkaziladi) —
      **birortasi yo'qolmaydi**. Tekshirish: id larni `GET request:state:<id>` bilan.
```

- [ ] **Step 6: Full suite + commit**

Run: `mvn -P '!frontend' clean test` — expect `BUILD SUCCESS`, 319 + the new tests, 0 failures.

```bash
git add -A && git commit -m "docs: adaptive CB backpressure (SAFETY 23 qayta yozildi, 40-qoida)"
```

---

## Phase 2 — Stop our own burst from opening the circuit

The circuit opened because ≥5 of 10 consecutive calls to one host failed while we were dispatching a 1000-message backlog at it. Bounding *our* concurrency per host makes the upstream's own limits irrelevant to us: the backlog drains at a rate the upstream can absorb, and the circuit stops opening in the first place.

`resilience4j-bulkhead` is on the classpath but `resilience4j-reactor` is **not**, so the reactive operators are unavailable. A plain semaphore is enough and adds no dependency.

### Task 6: Per-host concurrency limiter

**Files:**
- Create: `app/src/main/java/uz/greenwhite/gateway/http/HostConcurrencyLimiter.java`
- Modify: `app/src/main/java/uz/greenwhite/gateway/http/HttpRequestService.java`
- Modify: `app/src/main/java/uz/greenwhite/gateway/config/ApiProperties.java` (or wherever `gateway.api.*` lives)
- Test: `app/src/test/java/uz/greenwhite/gateway/http/HostConcurrencyLimiterTest.java`

**Interfaces:**
- Produces:
  - `boolean tryAcquire(String host)` — non-blocking permit.
  - `void release(String host)` — must run exactly once per successful acquire, on **both** the success and error paths of the reactive chain (`doFinally`).
  - `int inFlight(String host)`.

**Behaviour:** when no permit is available, `HttpRequestService` returns the **same** CB-blocked-shaped response it already builds (`buildCircuitBreakerResponse`), so Phase 1's park-and-recheck path handles it with no consumer changes at all. Use a distinct metric (`gateway.http.host.limiter.rejected`) so the two causes stay distinguishable in Grafana, and log at DEBUG (this will be common under load and must not spam).

Config: `gateway.api.max-concurrent-per-host` (`API_MAX_CONCURRENT_PER_HOST`), default **25**. Rationale to put in the javadoc: the observed burst was ~67 requests/second; 25 concurrent calls at a ~200 ms upstream latency sustains ~125 req/s, i.e. comfortably above the arrival rate while never presenting the upstream with hundreds of simultaneous connections.

- [ ] **Step 1: Write the failing test** — permits are per host and exhausted independently; release returns the permit; releasing a host that was never acquired is a no-op and does not create negative counts.
- [ ] **Step 2: Run — expect compile failure.**
- [ ] **Step 3: Implement** with `ConcurrentHashMap<String, Semaphore>` and `computeIfAbsent`.
- [ ] **Step 4: Wire into `HttpRequestService`** — acquire immediately before `circuitBreaker.acquirePermission()`; release in `.doFinally(...)` on the returned `Mono` so both completion and error release exactly once.
- [ ] **Step 5: Run the http test package** — expect PASS, and `HttpRequestServiceTest` unaffected.
- [ ] **Step 6: Commit.**

---

## Phase 3 — One incident, one alert

212 DLQ records produced 212 Telegram calls; Telegram answered 429 `retry after N` 298 times. The alert channel became useless exactly when it mattered.

### Task 7: Coalesce DLQ alerts

**Files:**
- Modify: `app/src/main/java/uz/greenwhite/gateway/notification/TelegramNotificationService.java`
- Modify: `app/src/main/java/uz/greenwhite/gateway/config/TelegramProperties.java`
- Test: `app/src/test/java/uz/greenwhite/gateway/notification/AlertCoalescingTest.java`

**Behaviour:**

1. Group by `(failureReasonClass, host)` where `failureReasonClass` is the reason with digits and ids stripped — `"Circuit breaker still open after 41293ms"` → `"Circuit breaker still open"`.
2. First alert in a group sends immediately (fast signal, unchanged behaviour for the common single-failure case).
3. Further alerts in the same group inside `gateway.telegram.coalesce-window-ms` (default 60000) are counted, not sent. When the window closes, send one summary: `"⚠️ 211 more DLQ records in 60s — Circuit breaker still open — smartup.online (first: 100:15073141)"`.
4. On HTTP 429, read `parameters.retry_after` from the body and suppress all sends for that many seconds (plus 1 s), counting what was suppressed and reporting it in the next message. Never retry a 429 immediately — that is what produced 298 failures.
5. Metric `gateway.notification.alert.coalesced`.

- [ ] **Step 1: Write the failing test** — 200 alerts in one window produce exactly 2 HTTP calls (first + summary); a 429 with `retry_after: 30` suppresses subsequent sends; distinct reason/host pairs are not merged.
- [ ] **Step 2: Run — expect failure** (today every call hits the wire).
- [ ] **Step 3: Implement** with a `ConcurrentHashMap<String, Window>` and the shared `cbRequeueTimer`-style scheduler; flush on window close.
- [ ] **Step 4: Run the notification tests** — expect PASS, existing `TelegramNotificationServiceTest` still green.
- [ ] **Step 5: Commit.**

---

## Rollout

1. Deploy **Phase 1 alone** first, during a quiet window (the 09:15 deploy was clean because traffic was idle; the 14:21 one created the burst that started this incident). Verify K1 and K4 from the manual plan.
2. Watch for one full day. `gateway_http_circuitbreaker_requeue_delayed_total` should be non-zero during upstream trouble, and `gateway_http_circuitbreaker_requeue_pending{flow}` should rise and return to 0 as each circuit recovers. **Amended after Task 5c:** `..._requeue_exhausted_total` is no longer expected to stay at 0 — the scheduler now enforces the deadline itself, so an outage that outlives `retry.max-message-age-ms` (5 min) legitimately produces `Circuit breaker still open after <ageMs>ms` DLQ records and increments that counter. What would signal a regression is the *opposite* pattern: `exhausted` climbing while `pending` never rises (the park is not happening), or DLQ records reading `Circuit breaker still open after <n> requeues` (the pre-v2.3.0 count-based give-up).
3. Then Phase 2, then Phase 3.
4. Remove the `RETRY_CB_MAX_REQUEUES=1500` mitigation from the production `.env` after Phase 1 is confirmed.

## Not in scope, deliberately

- **Pausing the Kafka listener container while a circuit is open.** It is the textbook backpressure answer and costs no memory, but circuits are per *host* while containers are per *flow*: one sick upstream would stall requests to every healthy one. Revisit only if the pending bound turns out to be the binding constraint in practice.
- **A delay topic** (`gateway.retry.30s` + its own consumer). Durable and memory-free, but it adds topics, consumers and ordering questions to a protected zone for a wait that the in-memory park already handles within the 5-minute budget.
- **Re-driving the 212 DLQ records from 2026-07-27.** The payload and URL are in the DLQ topic, but SAFETY-RULES 7 forbids automated replay and re-sending order exports is a business decision, not an engineering one.
