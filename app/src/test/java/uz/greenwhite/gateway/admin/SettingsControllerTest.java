package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import uz.greenwhite.gateway.admin.dto.SettingUpdateRequest;
import uz.greenwhite.gateway.admin.dto.SettingsRegistryResponse;
import uz.greenwhite.gateway.settings.SettingsService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock SettingsService settings;

    @AfterEach
    void clearSecurityContext() {
        // The controller() helper seeds a thread-local auth; clear it so it does not
        // leak into other test classes running on the same JVM thread.
        SecurityContextHolder.clearContext();
    }

    private SettingsController controller() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("server.port", "8090");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "x"));
        return new SettingsController(settings, null, env);
    }

    @Test
    void registryReturnsMetadataValuesAndPendingFlag() {
        // Broad stubs first: Mockito's last matching stub wins, so the specific
        // eq("gateway.api.base-url") stub must come after startsWith("gateway.").
        when(settings.get(startsWith("gateway."), any())).thenReturn("x");
        when(settings.get(startsWith("spring."), any())).thenReturn("x");
        when(settings.get(eq("gateway.api.base-url"), any())).thenReturn("https://live.example.com");
        when(settings.get(eq("server.port"), any())).thenReturn("8090");
        when(settings.get(eq("management.server.port"), any())).thenReturn("8091");
        when(settings.isRestartPending()).thenReturn(true);

        SettingsRegistryResponse r = controller().registry().getBody();

        assertTrue(r.restartPending());
        assertTrue(r.settings().stream().anyMatch(i ->
                i.key().equals("gateway.api.base-url")
                && i.value().equals("https://live.example.com")
                && i.effectClass().equals("LIVE")));
        assertTrue(r.settings().stream().anyMatch(i ->
                i.key().equals("server.port") && i.effectClass().equals("READ_ONLY")));
    }

    @Test
    void putRejectsReadOnlyKey() {
        SettingUpdateRequest req = new SettingUpdateRequest();
        req.setValue("9999");
        assertThrows(IllegalArgumentException.class,
                () -> controller().update("server.port", req));
        verify(settings, never()).set(eq("server.port"), any(), anyBoolean(), any());
    }

    @Test
    void putRejectsUnknownKey() {
        // F1b: a key not in the registry (e.g. an infra key an attacker hand-crafts a PUT
        // for) must be rejected — otherwise the PostProcessor would inject it on next boot.
        SettingUpdateRequest req = new SettingUpdateRequest();
        req.setValue("evil:9092");
        assertThrows(IllegalArgumentException.class,
                () -> controller().update("gateway.kafka.bootstrap-servers", req));
        verify(settings, never()).set(any(), any(), anyBoolean(), any());
    }

    @Test
    void putRequiresRestartKeySetsPendingFlag() {
        SettingUpdateRequest req = new SettingUpdateRequest();
        req.setValue("5");
        controller().update("gateway.retry.max-attempts", req);
        verify(settings).set("gateway.retry.max-attempts", "5", false, "admin");
        verify(settings).markRestartPending();
    }

    @Test
    void putLiveKeyDoesNotSetPendingFlag() {
        SettingUpdateRequest req = new SettingUpdateRequest();
        req.setValue("https://new.example.com");
        controller().update("gateway.api.base-url", req);
        verify(settings).set("gateway.api.base-url", "https://new.example.com", false, "admin");
        verify(settings, never()).markRestartPending();
    }
}
