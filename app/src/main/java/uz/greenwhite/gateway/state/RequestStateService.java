package uz.greenwhite.gateway.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.RedisProperties;
import uz.greenwhite.gateway.model.RequestState;
import uz.greenwhite.gateway.model.enums.ErrorSource;
import uz.greenwhite.gateway.model.enums.RequestStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestStateService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisProperties redisProperties;

    private static final String STATE_PREFIX = "request:state:";
    private static final String LOCK_PREFIX = "request:lock:";
    private static final String TOKEN_EVICTED_PREFIX = "request:token-evicted:";
    private static final String CB_REQUEUE_PREFIX = "request:cb-requeue:";

    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /** Lock tokens held by THIS instance; release is only valid for a token we own. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> ownedLockTokens =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ==================== STATE OPERATIONS ====================

    /**
     * Save request state to Redis
     */
    public void saveState(RequestState state) {
        String key = STATE_PREFIX + state.getCompositeId();
        state.setUpdatedAt(LocalDateTime.now());

        redisTemplate.opsForValue().set(key, state,
                redisProperties.getStateTtlHours(), TimeUnit.HOURS);
        log.debug("State saved: {} -> {} (TTL: {}h)", key, state.getStatus(),
                redisProperties.getStateTtlHours());
    }

    /**
     * Get request state from Redis
     */
    public Optional<RequestState> getState(String compositeId) {
        String key = STATE_PREFIX + compositeId;
        Object value = redisTemplate.opsForValue().get(key);

        if (value instanceof RequestState state) {
            return Optional.of(state);
        }
        return Optional.empty();
    }

    /**
     * Backwards-compatible overload; prefer the two-arg form so {@code /status} and
     * {@code /result} can gate on source.
     */
    public RequestState createInitialState(String compositeId) {
        return createInitialState(compositeId, null);
    }

    /**
     * Create initial state for a new request, recording which ingress it came from
     * ({@code "ORACLE"} | {@code "API"}). The polling endpoints refuse reads of
     * ORACLE-sourced requests, so this field must be persisted.
     */
    public RequestState createInitialState(String compositeId, String source) {
        RequestState state = RequestState.builder()
                .compositeId(compositeId)
                .status(RequestStatus.PROCESSING)
                .attemptCount(0)
                .source(source)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        saveState(state);
        return state;
    }

    /**
     * Update status
     */
    public void updateStatus(String compositeId, RequestStatus status) {
        getState(compositeId).ifPresent(state -> {
            state.setStatus(status);
            state.setUpdatedAt(LocalDateTime.now());
            saveState(state);
            log.info("Status updated: {} -> {}", compositeId, status);
        });
    }

    /**
     * Flip the {@code hasResult} flag on the stored state — set true once a
     * response body has been written to RocksDB and is available via
     * {@code /qosid/api/v1/requests/{id}/result}.
     */
    public void updateHasResult(String compositeId, boolean hasResult) {
        getState(compositeId).ifPresent(state -> {
            state.setHasResult(hasResult);
            state.setUpdatedAt(LocalDateTime.now());
            saveState(state);
            log.debug("hasResult updated: {} -> {}", compositeId, hasResult);
        });
    }

    /**
     * Mark as failed
     */
    public void markFailed(String compositeId, String error, ErrorSource source) {
        getState(compositeId).ifPresent(state -> {
            state.setStatus(RequestStatus.FAILED);
            state.setLastError(error);
            state.setErrorSource(source);
            state.setUpdatedAt(LocalDateTime.now());
            saveState(state);
            log.warn("Request marked as failed: {} - {}", compositeId, error);
        });
    }

    /**
     * Increment attempt count.
     * If Redis is unavailable or state not found, returns Integer.MAX_VALUE
     * to prevent infinite retry loops.
     */
    public int incrementAttempt(String compositeId) {
        try {
            Optional<RequestState> stateOpt = getState(compositeId);
            if (stateOpt.isPresent()) {
                RequestState state = stateOpt.get();
                state.setAttemptCount(state.getAttemptCount() + 1);
                state.setUpdatedAt(LocalDateTime.now());
                saveState(state);
                return state.getAttemptCount();
            }

            log.warn("State not found for incrementAttempt: {}. " +
                    "Returning MAX_VALUE to prevent infinite retry.", compositeId);
            return Integer.MAX_VALUE;

        } catch (Exception e) {
            log.error("Redis error during incrementAttempt for {}: {}. " +
                    "Returning MAX_VALUE to prevent infinite retry.", compositeId, e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Check if request is already completed
     */
    public boolean isCompleted(String compositeId) {
        return getState(compositeId)
                .map(state -> state.getStatus() == RequestStatus.COMPLETED ||
                        state.getStatus() == RequestStatus.FAILED)
                .orElse(false);
    }

    /**
     * Delete state
     */
    public void deleteState(String compositeId) {
        String key = STATE_PREFIX + compositeId;
        redisTemplate.delete(key);
        log.debug("State deleted: {}", compositeId);
    }

    // ==================== LOCK OPERATIONS ====================

    /**
     * Try to acquire lock (for idempotency). The lock value is a random owner
     * token so that release can be compare-and-delete — a lock that expired and
     * was re-acquired by another instance will NOT be deleted by us.
     */
    public boolean tryLock(String compositeId) {
        String key = LOCK_PREFIX + compositeId;
        String token = java.util.UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, token, redisProperties.getLockTtlSeconds(), TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(acquired)) {
            ownedLockTokens.put(compositeId, token);
            log.debug("Lock acquired: {}", compositeId);
            return true;
        }
        log.debug("Lock already exists: {}", compositeId);
        return false;
    }

    /**
     * Release lock — only if this instance still owns it (compare-and-delete).
     */
    public void releaseLock(String compositeId) {
        String token = ownedLockTokens.remove(compositeId);
        if (token == null) {
            log.debug("Release skipped — lock not owned by this instance: {}", compositeId);
            return;
        }
        String key = LOCK_PREFIX + compositeId;
        try {
            Long deleted = stringRedisTemplate.execute(COMPARE_AND_DELETE, java.util.List.of(key), token);
            if (deleted == null || deleted == 0) {
                log.warn("Lock {} expired and was taken over before release — not deleted", compositeId);
            } else {
                log.debug("Lock released: {}", compositeId);
            }
        } catch (Exception e) {
            log.warn("Redis error releasing lock {}: {} (TTL will reap it)", compositeId, e.getMessage());
        }
    }

    /**
     * Check if lock exists
     */
    public boolean isLocked(String compositeId) {
        String key = LOCK_PREFIX + compositeId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Get current attempt count.
     * If Redis is unavailable or state not found, returns Integer.MAX_VALUE
     * to prevent infinite retry loops.
     */
    public int getAttemptCount(String compositeId) {
        try {
            return getState(compositeId)
                    .map(RequestState::getAttemptCount)
                    .orElseGet(() -> {
                        log.warn("State not found for getAttemptCount: {}. " +
                                "Returning MAX_VALUE to prevent infinite retry.", compositeId);
                        return Integer.MAX_VALUE;
                    });
        } catch (Exception e) {
            log.error("Redis error during getAttemptCount for {}: {}. " +
                    "Returning MAX_VALUE to prevent infinite retry.", compositeId, e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

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

    /**
     * Count one CB-blocked re-queue for this request and return the new total.
     *
     * <p>SAFETY-RULES 23 requires the CB re-queue path to leave {@code attemptCount}
     * untouched, so this is a deliberately separate counter whose only job is to stop
     * the loop from running forever. The caller sends the message to the DLQ once the
     * total passes {@code gateway.retry.cb-max-requeues}.
     *
     * <p>Returns {@link Integer#MAX_VALUE} when Redis is unavailable — the same
     * fail-closed convention as {@link #incrementAttempt} (rule 8), so a Redis outage
     * degrades to an immediate DLQ rather than an unbounded re-queue loop.
     *
     * <p>TTL matches request state, so the counter dies with the request lifecycle.
     */
    public int incrementCbRequeue(String compositeId) {
        String key = CB_REQUEUE_PREFIX + compositeId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                log.warn("Redis returned null for CB requeue count {} — failing closed", compositeId);
                return Integer.MAX_VALUE;
            }
            redisTemplate.expire(key, redisProperties.getStateTtlHours(), TimeUnit.HOURS);
            return count.intValue();
        } catch (Exception e) {
            log.error("Redis unavailable for CB requeue count {} — failing closed: {}",
                    compositeId, e.getMessage());
            return Integer.MAX_VALUE;
        }
    }
}