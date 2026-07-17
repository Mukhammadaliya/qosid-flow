package uz.greenwhite.gateway.oauth2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.oauth2.client.OAuth2Client;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class OAuth2TokenRedisCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor refreshExecutor;

    private static final String TOKEN_KEY_PREFIX = "oauth2:token:";
    private static final String LOCK_KEY_PREFIX = "oauth2:lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(20);
    private static final long MARGIN_MS = 15_000;
    private static final int WAIT_ITERATIONS = 30;
    private static final long WAIT_SLEEP_MS = 500;

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    public OAuth2TokenRedisCache(StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 @Qualifier("oauth2RefreshExecutor") ThreadPoolTaskExecutor refreshExecutor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.refreshExecutor = refreshExecutor;
    }

    /**
     * Stale-while-revalidate token retrieval:
     *   1. fresh cache hit  → return immediately
     *   2. stale cache hit  → return immediately AND fire async refresh in background
     *   3. expired / absent → synchronous refresh (with distributed lock + wait fallback)
     *
     * Path (2) is what eliminates the thundering herd: once a token crosses 80%
     * of its lifetime, the very next caller silently kicks off a refresh while
     * still serving the valid token. No user-facing request waits on the network.
     */
    public Token getToken(String providerName, ProviderProperties properties, OAuth2Client client) {
        Token cached = getFromRedis(providerName);

        if (cached != null && !cached.isExpired()) {
            if (cached.isDueForRefresh()) {
                triggerAsyncRefresh(providerName, properties, client, cached);
            }
            log.debug("OAuth2 token cache HIT: {}", providerName);
            return cached;
        }

        String lockToken;
        try {
            lockToken = acquireLock(providerName);
        } catch (Exception e) {
            log.warn("Redis unavailable acquiring OAuth2 lock for {} — degrading: {}",
                    providerName, e.getMessage());
            if (cached != null) {
                return cached;                                    // stale token beats total failure
            }
            return performRefresh(providerName, properties, client, null); // direct uncached mint
        }

        if (lockToken == null) {
            log.debug("Another instance is refreshing token: {}, waiting...", providerName);
            return waitForToken(providerName, cached);
        }

        try {
            Token doubleCheck = getFromRedis(providerName);
            if (doubleCheck != null && !doubleCheck.isExpired()) {
                log.debug("OAuth2 token refreshed by another instance: {}", providerName);
                return doubleCheck;
            }

            Token newToken = performRefresh(providerName, properties, client, cached);
            saveToRedis(providerName, newToken);
            log.info("OAuth2 token saved to Redis: {}", providerName);
            return newToken;

        } catch (Exception e) {
            log.error("Failed to get/refresh OAuth2 token: {} - {}", providerName, e.getMessage());
            if (cached != null) {
                log.warn("Returning expired token as fallback: {}", providerName);
                return cached;
            }
            throw new RuntimeException("Failed to get OAuth2 token for: " + providerName, e);
        } finally {
            releaseLock(providerName, lockToken);
        }
    }

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

    // ==================== ASYNC REFRESH (stale-while-revalidate) ====================

    private void triggerAsyncRefresh(String providerName, ProviderProperties properties,
                                     OAuth2Client client, Token cached) {
        try {
            refreshExecutor.execute(() -> doAsyncRefresh(providerName, properties, client, cached));
        } catch (RejectedExecutionException e) {
            log.debug("Async OAuth2 refresh rejected (executor full): {}", providerName);
        }
    }

    private void doAsyncRefresh(String providerName, ProviderProperties properties,
                                OAuth2Client client, Token cached) {
        String lockToken;
        try {
            lockToken = acquireLock(providerName);
        } catch (Exception e) {
            log.debug("Async refresh skipped, Redis unavailable: {}", providerName);
            return;
        }
        if (lockToken == null) {
            log.debug("Async refresh skipped, another instance already refreshing: {}", providerName);
            return;
        }

        try {
            Token doubleCheck = getFromRedis(providerName);
            if (doubleCheck != null && !doubleCheck.isDueForRefresh()) {
                log.debug("Async refresh skipped, token already refreshed: {}", providerName);
                return;
            }

            log.info("Async OAuth2 token refresh started: {}", providerName);
            Token newToken = performRefresh(providerName, properties, client, cached);
            saveToRedis(providerName, newToken);
            log.info("Async OAuth2 token refresh completed: {}", providerName);
        } catch (Exception e) {
            log.warn("Async OAuth2 token refresh failed for {}: {} (cached token still valid until expiry)",
                    providerName, e.getMessage());
        } finally {
            releaseLock(providerName, lockToken);
        }
    }

    // ==================== REFRESH HELPERS ====================

    private Token performRefresh(String providerName, ProviderProperties properties,
                                 OAuth2Client client, Token cached) {
        if (cached != null && cached.refreshToken() != null) {
            log.info("Refreshing OAuth2 token: {}", providerName);
            try {
                return client.refreshAccessToken(properties, cached);
            } catch (Exception e) {
                log.warn("Token refresh failed for {}, getting new token: {}", providerName, e.getMessage());
                return client.getAccessToken(properties);
            }
        }
        log.info("Getting new OAuth2 token: {}", providerName);
        return client.getAccessToken(properties);
    }

    // ==================== REDIS OPERATIONS ====================

    private Token getFromRedis(String providerName) {
        try {
            String json = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + providerName);
            if (json == null) return null;
            return objectMapper.readValue(json, Token.class);
        } catch (Exception e) {
            log.warn("Failed to read token from Redis: {} - {}", providerName, e.getMessage());
            return null;
        }
    }

    private void saveToRedis(String providerName, Token token) {
        try {
            String json = objectMapper.writeValueAsString(token);
            long ttlMs = token.expiresIn() - MARGIN_MS;
            if (ttlMs > 0) {
                redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + providerName, json, Duration.ofMillis(ttlMs));
            } else {
                redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + providerName, json, Duration.ofSeconds(30));
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to save token to Redis: {} - {}", providerName, e.getMessage());
        }
    }

    // ==================== DISTRIBUTED LOCK ====================

    /** @return owner token when acquired, null when someone else holds the lock */
    private String acquireLock(String providerName) {
        String token = java.util.UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY_PREFIX + providerName, token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    private void releaseLock(String providerName, String token) {
        if (token == null) return;
        try {
            redisTemplate.execute(COMPARE_AND_DELETE,
                    java.util.List.of(LOCK_KEY_PREFIX + providerName), token);
        } catch (Exception e) {
            log.warn("Redis error releasing OAuth2 lock {}: {} (TTL will reap it)", providerName, e.getMessage());
        }
    }

    /**
     * Poll Redis up to WAIT_ITERATIONS × WAIT_SLEEP_MS for the lock holder to
     * publish a fresh token. Budget must comfortably exceed the OAuth2 endpoint's
     * worst-case response time so concurrent waiters don't all give up before the
     * single in-flight refresh completes.
     */
    private Token waitForToken(String providerName, Token fallback) {
        for (int i = 0; i < WAIT_ITERATIONS; i++) {
            try {
                Thread.sleep(WAIT_SLEEP_MS);
                Token token = getFromRedis(providerName);
                if (token != null) {
                    return token;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (fallback != null) {
            log.warn("Wait timeout, returning fallback token: {}", providerName);
            return fallback;
        }
        throw new RuntimeException("Failed to get OAuth2 token after waiting: " + providerName);
    }
}
