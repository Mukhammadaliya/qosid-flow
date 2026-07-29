package uz.greenwhite.gateway.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import uz.greenwhite.gateway.admin.dto.SettingsRegistryResponse;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @Mock SettingsService settings;

    private SettingsController controller() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("server.port", "8090");
        return new SettingsController(settings, null, env);
    }

    @Test
    void registryReturnsMetadataAndValues() {
        // Broad stubs first: Mockito's last matching stub wins, so the specific
        // eq("gateway.api.base-url") stub must come after startsWith("gateway.").
        when(settings.get(startsWith("gateway."), any())).thenReturn("x");
        when(settings.get(startsWith("spring."), any())).thenReturn("x");
        when(settings.get(eq("gateway.api.base-url"), any())).thenReturn("https://live.example.com");
        when(settings.get(eq("server.port"), any())).thenReturn("8090");
        when(settings.get(eq("management.server.port"), any())).thenReturn("8091");

        SettingsRegistryResponse r = controller().registry().getBody();

        assertTrue(r.settings().stream().anyMatch(i ->
                i.key().equals("gateway.api.base-url")
                && i.value().equals("https://live.example.com")
                && i.effectClass().equals("LIVE")));
        assertTrue(r.settings().stream().anyMatch(i ->
                i.key().equals("server.port") && i.effectClass().equals("READ_ONLY")));
    }

    @Test
    void putAlwaysReturns409StopRequired() {
        // Stop-to-edit: the running service refuses ALL settings writes — even for
        // otherwise-editable registry keys. Editing happens via the ops sidecar.
        ResponseEntity<Map<String, String>> res = controller().update("gateway.api.base-url");
        assertEquals(409, res.getStatusCode().value());
        assertEquals("stop-required", res.getBody().get("error"));
        verify(settings, never()).set(any(), any(), anyBoolean(), any());
    }

    @Test
    void putReadOnlyOrUnknownKeyAlsoReturns409() {
        assertEquals(409, controller().update("server.port").getStatusCode().value());
        assertEquals(409, controller().update("gateway.kafka.bootstrap-servers").getStatusCode().value());
        verify(settings, never()).set(any(), any(), anyBoolean(), any());
    }
}
