package uz.greenwhite.gateway.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uz.greenwhite.gateway.oauth2.client.JsonBodyOAuth2Client;
import uz.greenwhite.gateway.oauth2.client.StandardOAuth2Client;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceTokenServiceTest {

    @Mock SettingsService settings;
    @Mock StandardOAuth2Client standardClient;
    @Mock JsonBodyOAuth2Client jsonBodyClient;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private final ObjectMapper om = new ObjectMapper();

    private SourceTokenService service() {
        return new SourceTokenService(settings, standardClient, jsonBodyClient, redis, om);
    }

    private void stubOAuthSettings() {
        when(settings.get("gateway.source.auth.oauth2.token-url", "")).thenReturn("http://idp/token");
        when(settings.get("gateway.source.auth.oauth2.client-id", "")).thenReturn("cid");
        when(settings.getSecret("gateway.source.auth.oauth2.client-secret", "")).thenReturn("secret");
        when(settings.get("gateway.source.auth.oauth2.scope", "")).thenReturn("");
        when(settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", "")).thenReturn("");
    }

    @Test
    void cacheMissFetchesAndCachesUnderIsolatedKey() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("source:oauth2:token")).thenReturn(null); // miss
        stubOAuthSettings();
        when(settings.get("gateway.source.auth.oauth2.client-type", "standard")).thenReturn("standard");
        when(standardClient.getAccessToken(any(ProviderProperties.class)))
                .thenReturn(new Token("ACCESS", 3600_000, "Bearer", null));

        String token = service().getBearerToken();

        assertEquals("ACCESS", token);
        verify(standardClient).getAccessToken(any(ProviderProperties.class));
        // cached under the ISOLATED key (never oauth2:token:*)
        verify(valueOps).set(eq("source:oauth2:token"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void cacheHitReturnsWithoutFetch() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        Token cached = new Token("CACHED", 3600_000, "Bearer", null);
        when(valueOps.get("source:oauth2:token")).thenReturn(om.writeValueAsString(cached));

        String token = service().getBearerToken();

        assertEquals("CACHED", token);
        verify(standardClient, never()).getAccessToken(any());
    }

    @Test
    void configuredTtlCapsAtTokenLifetime() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("source:oauth2:token")).thenReturn(null);
        when(settings.get("gateway.source.auth.oauth2.token-url", "")).thenReturn("http://idp/token");
        when(settings.get("gateway.source.auth.oauth2.client-id", "")).thenReturn("cid");
        when(settings.getSecret("gateway.source.auth.oauth2.client-secret", "")).thenReturn("secret");
        when(settings.get("gateway.source.auth.oauth2.scope", "")).thenReturn("");
        when(settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", "")).thenReturn("99999"); // > token life
        when(settings.get("gateway.source.auth.oauth2.client-type", "standard")).thenReturn("standard");
        when(standardClient.getAccessToken(any(ProviderProperties.class)))
                .thenReturn(new Token("A", 60_000, "Bearer", null)); // 60s token

        service().getBearerToken();

        // TTL capped at 60 - 15 = 45s, not 99999
        verify(valueOps).set(eq("source:oauth2:token"), anyString(), eq(45L), eq(TimeUnit.SECONDS));
    }

    @Test
    void defaultClientTypeMintsThroughStandardClient() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("source:oauth2:token")).thenReturn(null);
        stubOAuthSettings();
        // client-type absent -> default "standard"
        when(settings.get("gateway.source.auth.oauth2.client-type", "standard")).thenReturn("standard");
        when(standardClient.getAccessToken(any(ProviderProperties.class)))
                .thenReturn(new Token("STD", 3600_000, "Bearer", null));

        assertEquals("STD", service().getBearerToken());

        verify(standardClient).getAccessToken(any(ProviderProperties.class));
        verify(jsonBodyClient, never()).getAccessToken(any());
    }

    @Test
    void biruniClientTypeMintsThroughJsonBodyClient() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("source:oauth2:token")).thenReturn(null);
        stubOAuthSettings();
        // legacy alias "biruni" must still resolve to the JSON-body client
        when(settings.get("gateway.source.auth.oauth2.client-type", "standard")).thenReturn("biruni");
        when(jsonBodyClient.getAccessToken(any(ProviderProperties.class)))
                .thenReturn(new Token("BIR", 3600_000, "Bearer", null));

        assertEquals("BIR", service().getBearerToken());

        verify(jsonBodyClient).getAccessToken(any(ProviderProperties.class));
        verify(standardClient, never()).getAccessToken(any());
    }

    @Test
    void jsonBodyClientTypeMintsThroughJsonBodyClient() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("source:oauth2:token")).thenReturn(null);
        stubOAuthSettings();
        // canonical new value "json-body" resolves to the JSON-body client
        when(settings.get("gateway.source.auth.oauth2.client-type", "standard")).thenReturn("json-body");
        when(jsonBodyClient.getAccessToken(any(ProviderProperties.class)))
                .thenReturn(new Token("JB", 3600_000, "Bearer", null));

        assertEquals("JB", service().getBearerToken());

        verify(jsonBodyClient).getAccessToken(any(ProviderProperties.class));
        verify(standardClient, never()).getAccessToken(any());
    }
}
