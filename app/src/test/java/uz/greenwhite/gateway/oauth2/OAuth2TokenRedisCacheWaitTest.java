package uz.greenwhite.gateway.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uz.greenwhite.gateway.oauth2.client.OAuth2Client;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The waiter path of {@link OAuth2TokenRedisCache}: a caller that finds another thread holding the
 * mint lock polls Redis for the published token.
 *
 * <p>Incident 2026-11-20: the provider's token endpoint answered 503 in ~90 ms, the lock holder
 * released the lock with no token published, and every other caller still slept out the full
 * 30 × 500 ms budget. All 20 Oracle HTTP threads sat in this loop, the pool rejected every other
 * host's requests ("Pool exhausted, re-queuing"), and one dead upstream stalled the whole
 * integration. A waiter must stop as soon as the holder is gone and nothing was published.
 */
class OAuth2TokenRedisCacheWaitTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final OAuth2Client client = mock(OAuth2Client.class);

    private OAuth2TokenRedisCache cache() {
        when(redis.opsForValue()).thenReturn(ops);
        return new OAuth2TokenRedisCache(redis, new ObjectMapper(), mock(ThreadPoolTaskExecutor.class));
    }

    /**
     * The holder failed and released the lock: the waiter takes the lock over and makes its own
     * single attempt, so it fails fast AND carries the real cause (the endpoint's 503) — the send
     * path needs that cause to tell "upstream down, park it" from "bad credentials, DLQ it".
     */
    @Test
    void waiterTakesOverTheLockWhenTheHolderFinishedWithoutATokenAndKeepsTheCause() {
        OAuth2TokenRedisCache cache = cache();
        when(ops.get("oauth2:token:p1")).thenReturn(null);                          // nothing cached, nothing published
        when(ops.setIfAbsent(eq("oauth2:lock:p1"), anyString(), any(Duration.class)))
                .thenReturn(false, true);                                           // someone else minting, then free
        when(redis.hasKey("oauth2:lock:p1")).thenReturn(false);                    // ...holder already gave up
        RuntimeException endpointDown = new RuntimeException("503 Service Unavailable");
        when(client.getAccessToken(any())).thenThrow(endpointDown);

        long start = System.nanoTime();
        assertThatThrownBy(() -> cache.getToken("p1", new ProviderProperties(), client))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("p1")
                .hasCause(endpointDown);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("must not sleep out the 15 s wait budget").isLessThan(2_000);
        verify(client, times(1)).getAccessToken(any()); // exactly one mint, under the lock
    }

    @Test
    void waiterThatTakesOverPublishesTheTokenItMinted() {
        OAuth2TokenRedisCache cache = cache();
        when(ops.get("oauth2:token:p1")).thenReturn(null);
        when(ops.setIfAbsent(eq("oauth2:lock:p1"), anyString(), any(Duration.class))).thenReturn(false, true);
        when(redis.hasKey("oauth2:lock:p1")).thenReturn(false);
        when(client.getAccessToken(any())).thenReturn(new Token("fresh", 3_600_000, "Bearer", null));

        assertThat(cache.getToken("p1", new ProviderProperties(), client).accessToken()).isEqualTo("fresh");
        verify(ops).set(eq("oauth2:token:p1"), anyString(), any(Duration.class));
    }

    @Test
    void waiterKeepsWaitingWhileTheLockIsHeldAndReturnsThePublishedToken() {
        OAuth2TokenRedisCache cache = cache();
        String json = "{\"accessToken\":\"abc\",\"tokenType\":\"Bearer\",\"expiresIn\":3600000,"
                + "\"createdAt\":" + System.currentTimeMillis() + "}";
        when(ops.get("oauth2:token:p1")).thenReturn(null, null, null, json);       // published on the 3rd poll
        when(ops.setIfAbsent(eq("oauth2:lock:p1"), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.hasKey("oauth2:lock:p1")).thenReturn(true);                     // holder still working

        assertThat(cache.getToken("p1", new ProviderProperties(), client).accessToken()).isEqualTo("abc");
    }

    @Test
    void redisErrorOnTheLockProbeKeepsTheOldWaitBehaviour() {
        OAuth2TokenRedisCache cache = cache();
        String json = "{\"accessToken\":\"abc\",\"tokenType\":\"Bearer\",\"expiresIn\":3600000,"
                + "\"createdAt\":" + System.currentTimeMillis() + "}";
        when(ops.get("oauth2:token:p1")).thenReturn(null, null, json);
        when(ops.setIfAbsent(eq("oauth2:lock:p1"), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.hasKey("oauth2:lock:p1")).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.getToken("p1", new ProviderProperties(), client).accessToken()).isEqualTo("abc");
    }
}
