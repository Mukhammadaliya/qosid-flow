# OAuth2 401 retry with fresh token — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When external API returns 401 to a request with an OAuth2 provider, gateway evicts the cached token and retries once with a fresh token. Prevents the periodic 3-hour DLQ alert spam caused by smartup OAuth occasionally issuing "stuck" tokens.

**Architecture:** Consumer-level retry in both `OracleRequestConsumer` and `ApiRequestConsumer`. On 401 + `oauth2_provider != null` + not-yet-evicted-for-this-key: evict token via `OAuth2ProviderService.evictToken`, mark eviction flag in Redis (`request:token-evicted:<key>`), re-queue without attempt increment (same pattern as CB-blocked). On second 401 (eviction flag set), fall through to existing permanent-failure path. Token eviction flag has same 72h TTL as request state.

**Tech Stack:** Java 21, Spring Boot 3.3.0, Resilience4j, Redis (StringRedisTemplate + RedisTemplate<String,Object>), Kafka, Lombok.

**Branch:** `debug` (per CLAUDE.md branching policy — no feature branches).

**Release:** v1.1.0 (this is a behavior change, minor bump).

---

## Task 1: Setup — switch to debug branch

**Files:**
- Modify: working tree

- [ ] **Step 1: Confirm git state and switch to debug**

```bash
git status --short && git branch --show-current
```
Expected: clean tree (apart from `.claude/`), current branch `master`.

- [ ] **Step 2: Sync debug from remote and check it out**

```bash
git fetch upstream && git checkout debug && git reset --hard upstream/debug
```
Expected: local `debug` matches `upstream/debug`. Working dir intact.

- [ ] **Step 3: Verify code base**

```bash
git log --oneline -3
```
Expected: latest debug commits visible. Gateway code present.

---

## Task 2: Add `evictToken` to OAuth2 cache layer

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/oauth2/OAuth2TokenRedisCache.java`

- [ ] **Step 1: Add `evictToken` method to `OAuth2TokenRedisCache`**

Open `src/main/java/uz/greenwhite/gateway/oauth2/OAuth2TokenRedisCache.java`. Add this method right after `getToken(...)` method (before the `// ==================== ASYNC REFRESH` comment block at line 90):

```java
    /**
     * Evict cached token for a provider — forces next request to mint a fresh one.
     * Used when downstream API rejects a syntactically-valid token (e.g. smartup
     * OAuth occasionally issues "stuck" tokens that pass /security/oauth/token but
     * are rejected by /b/trade/api/*). Evicting then retrying with a fresh mint
     * recovers within one Kafka round-trip instead of waiting for 3h TTL expiry.
     */
    public void evictToken(String providerName) {
        Boolean deleted = redisTemplate.delete(TOKEN_KEY_PREFIX + providerName);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("OAuth2 token cache evicted: {}", providerName);
        } else {
            log.debug("OAuth2 token cache eviction no-op (not present): {}", providerName);
        }
    }
```

- [ ] **Step 2: Compile to verify no syntax errors**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS, no errors.

---

## Task 3: Expose eviction via `OAuth2ProviderService`

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/oauth2/OAuth2ProviderService.java`

- [ ] **Step 1: Add `evictToken` delegation method**

Open `src/main/java/uz/greenwhite/gateway/oauth2/OAuth2ProviderService.java`. Add this method right after `evictProviderCache(...)` (line 49):

```java
    /**
     * Evict cached OAuth2 token for a provider (provider config cache stays intact).
     * Used by request consumers on API 401 to force a fresh token mint on retry.
     */
    public void evictToken(String providerName) {
        tokenRedisCache.evictToken(providerName);
    }
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

---

## Task 4: Track per-request token eviction flag in `RequestStateService`

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/state/RequestStateService.java`

- [ ] **Step 1: Add eviction flag prefix constant**

Open `src/main/java/uz/greenwhite/gateway/state/RequestStateService.java`. Right after line 25 (`LOCK_PREFIX`), add:

```java
    private static final String TOKEN_EVICTED_PREFIX = "request:token-evicted:";
```

- [ ] **Step 2: Add `markTokenEvicted` and `isTokenEvicted` methods**

Add these two methods at the end of the class, just before the closing `}` brace:

```java
    /**
     * Mark this request as having had its OAuth2 token evicted once already.
     * Set with the same TTL as request state, so the flag dies naturally with the
     * request lifecycle. Prevents an infinite evict→retry→evict loop when the 401
     * persists despite a fresh token (real auth misconfiguration).
     */
    public void markTokenEvicted(String compositeId) {
        String key = TOKEN_EVICTED_PREFIX + compositeId;
        try {
            redisTemplate.opsForValue().set(key, "1",
                    redisProperties.getStateTtlHours(), TimeUnit.HOURS);
            log.debug("Token eviction flag set: {}", compositeId);
        } catch (Exception e) {
            log.warn("Redis error setting token-evicted flag for {}: {}", compositeId, e.getMessage());
        }
    }

    /**
     * Check whether {@link #markTokenEvicted} has already been called for this
     * request. If Redis is unavailable returns {@code true} (fail-closed) so we
     * don't accidentally enter an evict loop during a Redis outage.
     */
    public boolean isTokenEvicted(String compositeId) {
        String key = TOKEN_EVICTED_PREFIX + compositeId;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Redis error checking token-evicted flag for {}: {}. " +
                    "Returning true to avoid evict loop.", compositeId, e.getMessage());
            return true;
        }
    }
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

---

## Task 5: Wire 401-retry into `OracleRequestConsumer`

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java`

- [ ] **Step 1: Add `OAuth2ProviderService` import**

Open `src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java`. Add this import alongside the existing imports (after the `uz.greenwhite.gateway.metrics.GatewayMetrics` import, around line 15):

```java
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
```

- [ ] **Step 2: Add field and constructor parameter**

Add this field declaration right after the existing `private final KafkaProperties kafkaProperties;` at line 38:

```java
    private final OAuth2ProviderService oAuth2ProviderService;
```

Update the constructor at line 40-54 — add `OAuth2ProviderService oAuth2ProviderService` as the last constructor parameter and assign it in the body. The full updated constructor:

```java
    public OracleRequestConsumer(
            HttpRequestService httpRequestService,
            RequestStateService requestStateService,
            RequestProducer requestProducer,
            RetryProperties retryProperties,
            @Qualifier("oracleHttpExecutor") ThreadPoolTaskExecutor httpExecutor,
            GatewayMetrics metrics,
            KafkaProperties kafkaProperties,
            OAuth2ProviderService oAuth2ProviderService) {
        this.httpRequestService = httpRequestService;
        this.requestStateService = requestStateService;
        this.requestProducer = requestProducer;
        this.retryProperties = retryProperties;
        this.httpExecutor = httpExecutor;
        this.metrics = metrics;
        this.kafkaProperties = kafkaProperties;
        this.oAuth2ProviderService = oAuth2ProviderService;
    }
```

- [ ] **Step 3: Insert 401-retry branch into `handleFailedResponse`**

Locate `handleFailedResponse` at line 192. After the CIRCUIT_BREAKER block (lines 197-202), and BEFORE the existing `int attemptCount = requestStateService.incrementAttempt(key);` line, insert this block:

```java
        if (httpStatus == 401 && message.getOauth2Provider() != null
                && !requestStateService.isTokenEvicted(key)) {
            metrics.getHttpRetry().increment();
            log.warn("[ORACLE] 401 from API with OAuth2 provider — evicting token and requeuing: {} (provider={})",
                    key, message.getOauth2Provider());
            oAuth2ProviderService.evictToken(message.getOauth2Provider());
            requestStateService.markTokenEvicted(key);
            requestProducer.sendOracleRequest(message);
            return;
        }
```

The full updated `handleFailedResponse` body should now read:

```java
    private void handleFailedResponse(String key, RequestMessage message, ResponseMessage response) {
        int httpStatus = response != null ? response.getHttpStatus() : 0;
        String errorMessage = response != null ? response.getErrorMessage() : "Unknown error";
        String errorSourceStr = response != null ? response.getErrorSource() : null;

        if ("CIRCUIT_BREAKER".equals(errorSourceStr)) {
            metrics.getHttpRetry().increment();
            log.warn("[ORACLE] CB-blocked, requeueing without attempt increment: {}", key);
            requestProducer.sendOracleRequest(message);
            return;
        }

        if (httpStatus == 401 && message.getOauth2Provider() != null
                && !requestStateService.isTokenEvicted(key)) {
            metrics.getHttpRetry().increment();
            log.warn("[ORACLE] 401 from API with OAuth2 provider — evicting token and requeuing: {} (provider={})",
                    key, message.getOauth2Provider());
            oAuth2ProviderService.evictToken(message.getOauth2Provider());
            requestStateService.markTokenEvicted(key);
            requestProducer.sendOracleRequest(message);
            return;
        }

        int attemptCount = requestStateService.incrementAttempt(key);
        boolean isOAuth2 = "OAUTH2".equals(errorSourceStr);
        boolean isRetryable = retryProperties.isRetryable(httpStatus) && !isOAuth2;
        boolean hasAttemptsLeft = attemptCount < retryProperties.getMaxAttempts();

        if (isRetryable && hasAttemptsLeft) {
            metrics.getHttpRetry().increment();
            log.warn("[ORACLE] Retryable error for {}: status={}, attempt {}/{}",
                    key, httpStatus, attemptCount, retryProperties.getMaxAttempts());
            requestProducer.sendOracleRequest(message);
        } else {
            if (isOAuth2) {
                log.warn("[ORACLE] Non-retryable OAuth2 error for {}", key);
            }
            ErrorSource source = ErrorSource.fromString(errorSourceStr);
            handlePermanentFailure(key, message, httpStatus, errorMessage, source);
        }
    }
```

- [ ] **Step 4: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

---

## Task 6: Wire 401-retry into `ApiRequestConsumer`

**Files:**
- Modify: `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java`

- [ ] **Step 1: Add `OAuth2ProviderService` import**

Open `src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java`. Add this import after the `uz.greenwhite.gateway.metrics.GatewayMetrics` import (around line 15):

```java
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
```

- [ ] **Step 2: Add field and constructor parameter**

Add this field declaration after `private final KafkaProperties kafkaProperties;` at line 38:

```java
    private final OAuth2ProviderService oAuth2ProviderService;
```

Update the constructor — add `OAuth2ProviderService oAuth2ProviderService` as last parameter:

```java
    public ApiRequestConsumer(
            HttpRequestService httpRequestService,
            RequestStateService requestStateService,
            RequestProducer requestProducer,
            RetryProperties retryProperties,
            @Qualifier("apiHttpExecutor") ThreadPoolTaskExecutor httpExecutor,
            GatewayMetrics metrics,
            KafkaProperties kafkaProperties,
            OAuth2ProviderService oAuth2ProviderService) {
        this.httpRequestService = httpRequestService;
        this.requestStateService = requestStateService;
        this.requestProducer = requestProducer;
        this.retryProperties = retryProperties;
        this.httpExecutor = httpExecutor;
        this.metrics = metrics;
        this.kafkaProperties = kafkaProperties;
        this.oAuth2ProviderService = oAuth2ProviderService;
    }
```

- [ ] **Step 3: Insert 401-retry branch into `handleFailedResponse`**

Locate `handleFailedResponse` at line 192. After the CIRCUIT_BREAKER block, before `int attemptCount = ...`, insert:

```java
        if (httpStatus == 401 && message.getOauth2Provider() != null
                && !requestStateService.isTokenEvicted(key)) {
            metrics.getHttpRetry().increment();
            log.warn("[API] 401 from API with OAuth2 provider — evicting token and requeuing: {} (provider={})",
                    key, message.getOauth2Provider());
            oAuth2ProviderService.evictToken(message.getOauth2Provider());
            requestStateService.markTokenEvicted(key);
            requestProducer.sendApiRequest(message);
            return;
        }
```

- [ ] **Step 4: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

---

## Task 7: Update VERSION.md and ai-context docs

**Files:**
- Modify: `VERSION.md`
- Modify: `ai-context/SAFETY-RULES.md`
- Modify: `ai-context/ARCHITECTURE.md`
- Modify: `CLAUDE.md` (Top 5 safety rules)

- [ ] **Step 1: Bump VERSION.md**

Replace the entire content of `VERSION.md` with:

```
1.1.0
```

- [ ] **Step 2: Add safety rule for the eviction-once invariant**

Open `ai-context/SAFETY-RULES.md`. In the "NEVER DO" / numbered safety rules section, add a new rule (find the appropriate spot — look for OAuth2-related rules):

```markdown
N. **Never call `OAuth2ProviderService.evictToken` without also setting `RequestStateService.markTokenEvicted`** — without the flag, a persistent 401 from upstream creates an evict→retry→evict loop that hammers the OAuth endpoint forever (`OracleRequestConsumer.handleFailedResponse`, `ApiRequestConsumer.handleFailedResponse`).
```

Renumber subsequent rules if necessary.

- [ ] **Step 3: Update ARCHITECTURE.md retry behavior section**

Open `ai-context/ARCHITECTURE.md`. Find the section describing consumer retry / DLQ logic (search for `CIRCUIT_BREAKER` or `retryable-statuses`). Below the CB-aware retry bullet, add:

```markdown
- **OAuth2-aware 401 retry (v1.1.0)** — when an upstream API returns 401 to a request whose `oauth2_provider` is set, the consumer evicts the cached token (`OAuth2ProviderService.evictToken`) and re-queues the request without incrementing the attempt counter. A per-request flag (`request:token-evicted:<compositeId>`) prevents repeating the eviction more than once per request — on the second 401 the request falls through to the standard permanent-failure path. This recovers from smartup's periodic "stuck token" glitch in one Kafka round-trip instead of waiting 3h for token TTL.
```

- [ ] **Step 4: Update CLAUDE.md Top 5 if applicable**

Open `CLAUDE.md`. The Top 5 safety rules include rule 4 ("Never remove Integer.MAX_VALUE safety net"). The new rule from Step 2 is similar in spirit (preventing infinite loops). If it adds value to surface in Top 5, replace one of the lower-priority entries; otherwise leave Top 5 unchanged and rely on the full SAFETY-RULES.md.

Decision: **leave Top 5 unchanged** — the new rule is narrow (evict-loop prevention) compared to the systemic risks already in Top 5. Add to detailed SAFETY-RULES.md only.

---

## Task 8: Build, manual verify, commit, push, PR

**Files:**
- Test: prod (smoke test using existing simulation skill)
- Modify: git history (commits)

- [ ] **Step 1: Full clean build**

```bash
mvn clean package -DskipTests
```
Expected: BUILD SUCCESS, JAR produced at `target/request-gateway-service-1.0.0-SNAPSHOT.jar`.

- [ ] **Step 2: Stage and commit changes**

```bash
git add src/main/java/uz/greenwhite/gateway/oauth2/OAuth2TokenRedisCache.java \
        src/main/java/uz/greenwhite/gateway/oauth2/OAuth2ProviderService.java \
        src/main/java/uz/greenwhite/gateway/state/RequestStateService.java \
        src/main/java/uz/greenwhite/gateway/kafka/consumer/oracle/OracleRequestConsumer.java \
        src/main/java/uz/greenwhite/gateway/kafka/consumer/api/ApiRequestConsumer.java \
        VERSION.md \
        ai-context/SAFETY-RULES.md \
        ai-context/ARCHITECTURE.md
git commit -m "$(cat <<'EOF'
feat: 401-retry-with-fresh-token to absorb smartup stuck-token glitch (v1.1.0)

When upstream API returns 401 to a request whose oauth2_provider is set,
consumer now evicts the cached token and re-queues once (no attempt increment).
A per-request flag (request:token-evicted:<key>) prevents evict→retry loops on
genuine auth failures: second 401 falls through to the existing
permanent-failure path.

Recovers from smartup OAuth's periodic "stuck token" issuance — tokens that
pass /security/oauth/token but are rejected by /b/trade/api/* — in one Kafka
round-trip instead of waiting for the 3h token TTL. Fixes the recurring
~2000-alert/day Telegram floods that v1.0.8 (CB-aware) and v1.0.9
(stale-while-revalidate) did not address.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Push debug branch**

```bash
git push upstream debug
```
Expected: branch updated.

- [ ] **Step 4: Open PR debug → master**

```bash
gh pr create --base master --head debug --title "feat: OAuth2 401-retry-with-fresh-token (v1.1.0)" --body "$(cat <<'EOF'
## Summary

- On API 401 with `oauth2_provider` set, evict cached token and re-queue once before falling through to permanent failure
- Per-request `request:token-evicted:<key>` flag prevents evict-loop on genuine auth failures
- Targets smartup's "stuck token" glitch — tokens issued correctly but rejected by `/b/trade/api/*`
- Bumps version to v1.1.0 (behavior change, minor bump)

## Test plan

- [ ] `mvn clean package -DskipTests` succeeds
- [ ] On prod (`/opt/services/qosid-flow`): pull, rebuild, restart `gateway-service.service`
- [ ] Trigger known 401-prone provider (or wait for natural smartup stuck token)
- [ ] Verify journal shows `[ORACLE] 401 from API with OAuth2 provider — evicting token and requeuing` once per affected key
- [ ] Verify follow-up retry returns 200 (success) or, if genuine auth issue, single permanent failure (not loop)
- [ ] Verify Telegram DLQ alert volume drops dramatically vs prior 24h

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
Expected: PR URL printed.

- [ ] **Step 5: Manual smoke test on prod (post-deploy)**

After PR merged + tagged `v1.1.0` + deployed (`git pull && mvn clean package -DskipTests && systemctl restart gateway-service.service`):

Live-tail the journal and look for the new log line during the next 401 incident:

```bash
journalctl -u gateway-service.service -f --no-pager | grep --line-buffered -E "evicting token and requeuing|HTTP response.*status=200|HTTP request failed.*401"
```

Expected sequence on a 401 incident:
1. `HTTP request failed [CB: cb-...]: 100:NNN -> 401 Unauthorized`
2. `[ORACLE] 401 from API with OAuth2 provider — evicting token and requeuing: 100:NNN (provider=...)`
3. `OAuth2 token saved to Redis: <provider>` (gateway minted fresh)
4. `HTTP response [CB: cb-...]: 100:NNN -> status=200, time=Xms`

If sequence does NOT recover (still 401 on retry), the eviction flag prevents loop and request goes to DLQ once — confirms safety-net behavior.

- [ ] **Step 6: 24h verification**

```bash
journalctl -u gateway-service.service --since "24 hours ago" --no-pager | grep -c "DLQ alert sent to Telegram"
```

Expected: significant drop vs pre-v1.1.0 baseline (~2000/day → low double digits or single per genuine outage).

---

## Self-Review

**Spec coverage:**
- ✅ Token eviction layer: Tasks 2, 3
- ✅ Per-request eviction tracking: Task 4
- ✅ Oracle consumer wiring: Task 5
- ✅ API consumer wiring: Task 6
- ✅ Version bump + docs: Task 7
- ✅ Build + verify + ship: Task 8

**Placeholder scan:** No "TBD" / "implement later" / "similar to N" / placeholder text. All code blocks contain final code.

**Type consistency:**
- `OAuth2ProviderService.evictToken(String providerName)` — Task 3 declaration, Tasks 5 & 6 callers
- `RequestStateService.markTokenEvicted(String compositeId)` and `isTokenEvicted(String compositeId)` — Task 4 declarations, Tasks 5 & 6 callers
- `OAuth2TokenRedisCache.evictToken(String providerName)` — Task 2 declaration, Task 3 caller (delegate)

All names match across tasks.

**Behavior invariants verified:**
- Eviction without flag = potential loop → flag write is on the same code path as eviction in Tasks 5, 6
- Real 401 (config issue) → flag set on first attempt, second 401 falls through normal permanent-failure path → bounded
- Redis outage during `isTokenEvicted` → returns `true` → no eviction → falls through to existing logic → safe (worst case: same as pre-v1.1.0 behavior)
- Token eviction race with another request → both will see no token → both mint → one wins via existing distributed lock in `OAuth2TokenRedisCache.acquireLock` → safe
