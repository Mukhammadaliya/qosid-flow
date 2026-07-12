package uz.greenwhite.gateway.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceAuthServiceTest {

    @Mock SettingsService settings;
    @Mock SourceTokenService sourceTokenService;
    @Mock GatewayProperties gatewayProperties;

    private SourceAuthService service() {
        return new SourceAuthService(settings, sourceTokenService, gatewayProperties);
    }

    @Test
    void basicFromSettings() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("basic");
        when(settings.get("gateway.source.auth.basic.username", "")).thenReturn("u");
        when(settings.getSecret("gateway.source.auth.basic.password", "")).thenReturn("p");

        Map<String, String> h = service().authHeaders();
        assertTrue(h.get("Authorization").startsWith("Basic "));
    }

    @Test
    void basicFallsBackToYamlWhenSettingsBlank() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("basic");
        when(settings.get("gateway.source.auth.basic.username", "")).thenReturn("");
        when(settings.getSecret("gateway.source.auth.basic.password", "")).thenReturn("");
        when(gatewayProperties.getUsername()).thenReturn("yamlUser");
        when(gatewayProperties.getPassword()).thenReturn("yamlPass");

        Map<String, String> h = service().authHeaders();
        assertTrue(h.get("Authorization").startsWith("Basic "));
    }

    @Test
    void apikeyUsesConfiguredHeaderAndValue() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("apikey");
        when(settings.get("gateway.source.auth.apikey.header", "X-Api-Key")).thenReturn("X-Api-Key");
        when(settings.getSecret("gateway.source.auth.apikey.value", "")).thenReturn("k123");

        Map<String, String> h = service().authHeaders();
        assertEquals("k123", h.get("X-Api-Key"));
        assertFalse(h.containsKey("Authorization"));
    }

    @Test
    void oauth2UsesBearerFromTokenService() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("oauth2");
        when(sourceTokenService.getBearerToken()).thenReturn("TKN");

        Map<String, String> h = service().authHeaders();
        assertEquals("Bearer TKN", h.get("Authorization"));
    }

    @Test
    void typeMatchIsCaseInsensitive() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("APIKEY");
        when(settings.get("gateway.source.auth.apikey.header", "X-Api-Key")).thenReturn("X-Api-Key");
        when(settings.getSecret("gateway.source.auth.apikey.value", "")).thenReturn("k");

        Map<String, String> h = service().authHeaders();
        assertEquals("k", h.get("X-Api-Key"));   // "APIKEY" resolved to the apikey branch
    }

    @Test
    void unknownTypeFallsBackToBasic() {
        when(settings.get("gateway.source.auth.type", "basic")).thenReturn("weird");
        when(settings.get("gateway.source.auth.basic.username", "")).thenReturn("");
        when(settings.getSecret("gateway.source.auth.basic.password", "")).thenReturn("");
        when(gatewayProperties.getUsername()).thenReturn("u");
        when(gatewayProperties.getPassword()).thenReturn("p");

        Map<String, String> h = service().authHeaders();
        assertTrue(h.get("Authorization").startsWith("Basic "));
    }
}
