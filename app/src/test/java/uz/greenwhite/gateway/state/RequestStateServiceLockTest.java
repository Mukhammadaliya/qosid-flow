package uz.greenwhite.gateway.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import uz.greenwhite.gateway.config.RedisProperties;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestStateServiceLockTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock ValueOperations<String, String> stringValueOps;
    @Mock RedisProperties redisProperties;

    @Test
    void releaseAfterAcquireUsesCompareAndDelete() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        when(redisProperties.getLockTtlSeconds()).thenReturn(300L);
        when(stringValueOps.setIfAbsent(eq("request:lock:1:2"), anyString(), eq(300L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        RequestStateService service =
                new RequestStateService(redisTemplate, stringRedisTemplate, redisProperties);

        assertTrue(service.tryLock("1:2"));
        service.releaseLock("1:2");

        // released via owner-checked script, not blind delete
        verify(stringRedisTemplate).execute(any(RedisScript.class), eq(List.of("request:lock:1:2")), anyString());
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void releaseWithoutOwnershipIsNoOp() {
        RequestStateService service =
                new RequestStateService(redisTemplate, stringRedisTemplate, redisProperties);

        service.releaseLock("9:9"); // never acquired by this instance

        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(redisTemplate, never()).delete(anyString());
    }
}
