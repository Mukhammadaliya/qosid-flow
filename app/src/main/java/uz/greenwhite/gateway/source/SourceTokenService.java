package uz.greenwhite.gateway.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.oauth2.client.JsonBodyOAuth2Client;
import uz.greenwhite.gateway.oauth2.client.OAuth2Client;
import uz.greenwhite.gateway.oauth2.client.StandardOAuth2Client;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.concurrent.TimeUnit;

/**
 * OAuth2 client_credentials token cache for the SOURCE channel (source pull/save,
 * e.g. Biruni). Deliberately isolated from the per-request provider cache ({@code oauth2:token:*})
 * by using the reserved key {@code source:oauth2:token} — a source-channel token
 * must never collide with, or be evicted by, a per-request forwarding provider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceTokenService {

    private static final String CACHE_KEY = "source:oauth2:token";
    private static final long MARGIN_SECONDS = 15;

    private final SettingsService settings;
    private final StandardOAuth2Client standardClient;
    private final JsonBodyOAuth2Client jsonBodyClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** @return a valid access token for the source channel (cached or freshly minted). */
    public String getBearerToken() {
        String cached = readCache();
        if (cached != null) {
            return cached;
        }
        Token token = selectClient().getAccessToken(buildProps());
        cache(token);
        return token.accessToken();
    }

    /**
     * Selects the token-mint client per {@code gateway.source.auth.oauth2.client-type}:
     * {@code json-body} (or the legacy alias {@code biruni}) = JSON body posted to the
     * token endpoint (Biruni-style sources), else (default {@code standard}) =
     * RFC 6749 form-urlencoded. Package-private so the admin test path shares exactly
     * this selection logic.
     */
    OAuth2Client selectClient() {
        String clientType = settings.get("gateway.source.auth.oauth2.client-type", "standard");
        // "biruni" kept as a backward-compat alias for the pre-rename config value.
        boolean jsonBody = "json-body".equalsIgnoreCase(clientType) || "biruni".equalsIgnoreCase(clientType);
        return jsonBody ? jsonBodyClient : standardClient;
    }

    private String readCache() {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            if (json == null) return null;
            Token token = objectMapper.readValue(json, Token.class);
            return token.isExpired() ? null : token.accessToken();
        } catch (Exception e) {
            log.warn("Source token cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private void cache(Token token) {
        try {
            long tokenTtl = token.expiresIn() / 1000 - MARGIN_SECONDS;
            long ttl = tokenTtl;
            String configured = settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", "");
            if (configured != null && !configured.isBlank()) {
                try {
                    long cfg = Long.parseLong(configured.trim());
                    ttl = Math.min(cfg, tokenTtl);
                } catch (NumberFormatException ignored) { /* keep tokenTtl */ }
            }
            if (ttl <= 0) ttl = 1;
            redisTemplate.opsForValue().set(CACHE_KEY, objectMapper.writeValueAsString(token), ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Source token cache write failed: {}", e.getMessage());
        }
    }

    private ProviderProperties buildProps() {
        return ProviderProperties.builder()
                .type(settings.get("gateway.source.auth.oauth2.client-type", "standard"))
                .tokenUrl(settings.get("gateway.source.auth.oauth2.token-url", ""))
                .clientId(settings.get("gateway.source.auth.oauth2.client-id", ""))
                .clientSecret(settings.getSecret("gateway.source.auth.oauth2.client-secret", ""))
                .scope(settings.get("gateway.source.auth.oauth2.scope", ""))
                .build();
    }
}
