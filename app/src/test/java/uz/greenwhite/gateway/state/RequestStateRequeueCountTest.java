package uz.greenwhite.gateway.state;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uz.greenwhite.gateway.config.RedisProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CB-blocked re-queue counter.
 *
 * <p>SAFETY-RULES 23 keeps the CB re-queue path free of {@code attemptCount}
 * increments, so this separate counter is what bounds the loop. On a Redis outage
 * it returns {@link Integer#MAX_VALUE} — the same fail-closed convention as
 * {@code getAttemptCount}/{@code incrementAttempt} (rule 8), so a Redis outage
 * degrades to "straight to DLQ", never to an unbounded loop.
 */
class RequestStateRequeueCountTest {

    @SuppressWarnings("unchecked")
    private RequestStateService service(ValueOperations<String, Object> ops, boolean redisBroken) {
        RedisTemplate<String, Object> tpl = mock(RedisTemplate.class);
        if (redisBroken) {
            when(tpl.opsForValue()).thenThrow(new RuntimeException("redis down"));
        } else {
            when(tpl.opsForValue()).thenReturn(ops);
        }
        return new RequestStateService(tpl, mock(StringRedisTemplate.class), new RedisProperties());
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementReturnsGrowingCount() {
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);
        RequestStateService svc = service(ops, false);

        assertEquals(1, svc.incrementCbRequeue("1:2"));
        assertEquals(2, svc.incrementCbRequeue("1:2"));
        assertEquals(3, svc.incrementCbRequeue("1:2"));
    }

    @Test
    void incrementFailsClosedWhenRedisDown() {
        // Redis down => treated as "cap already exceeded" => DLQ, never an endless loop.
        assertEquals(Integer.MAX_VALUE, service(null, true).incrementCbRequeue("1:2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void incrementFailsClosedWhenRedisReturnsNull() {
        ValueOperations<String, Object> ops = mock(ValueOperations.class);
        when(ops.increment(anyString())).thenReturn(null);
        assertEquals(Integer.MAX_VALUE, service(ops, false).incrementCbRequeue("1:2"));
    }
}
