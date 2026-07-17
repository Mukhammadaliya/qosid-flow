package uz.greenwhite.gateway.oracle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BiruniClientAuthTest {

    @Mock GatewayProperties properties;
    @Mock TenantMappingService tenantMappingService;
    @Mock SourceAuthService sourceAuthService;
    @Mock SettingsService settingsService;

    @Test
    void pullAsksSourceAuthServiceForHeaders() {
        when(properties.getBaseUrl()).thenReturn("http://localhost:0"); // unreachable → pull fails after auth resolved
        when(properties.getConnectionTimeout()).thenReturn(1);
        when(properties.getRequestPullUri()).thenReturn("/pull");
        // No DB override — SettingsService falls back to the yaml default.
        when(settingsService.get(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(sourceAuthService.authHeaders()).thenReturn(Map.of("Authorization", "Basic xxx"));

        BiruniClient client = new BiruniClient(properties, tenantMappingService, sourceAuthService, settingsService);
        try { client.pullRequests(); } catch (Exception ignored) { /* connection fails, that's fine */ }

        // The point: auth headers were requested from the service, not hardcoded.
        verify(sourceAuthService).authHeaders();
    }
}
