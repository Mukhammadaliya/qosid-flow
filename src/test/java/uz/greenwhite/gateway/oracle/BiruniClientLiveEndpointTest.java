package uz.greenwhite.gateway.oracle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.model.ResponseSaveRequest;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the Biruni source legs target the LIVE base-url + uri read from
 * {@link SettingsService} at request time, not the startup-bound
 * {@link GatewayProperties} value — so admin edits apply without restart.
 */
@ExtendWith(MockitoExtension.class)
class BiruniClientLiveEndpointTest {

    @Mock SettingsService settings;
    @Mock GatewayProperties props;
    @Mock TenantMappingService tenantMappingService;
    @Mock SourceAuthService sourceAuthService;

    @Test
    void pullUsesLiveBaseUrlAndUri() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://live-source.example.com/api/pull"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        // Startup-bound yaml values point elsewhere — they must NOT be used.
        when(settings.get(eq("gateway.source.base-url"), any())).thenReturn("https://live-source.example.com");
        when(settings.get(eq("gateway.source.request-pull-uri"), any())).thenReturn("/api/pull");
        when(sourceAuthService.authHeaders()).thenReturn(Map.of("Authorization", "Basic xxx"));

        BiruniClient client = new BiruniClient(builder.build(), props, tenantMappingService, sourceAuthService, settings);
        client.pullRequests();

        server.verify();
    }

    @Test
    void saveUsesLiveBaseUrlAndUri() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://live-source.example.com/api/save"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        when(settings.get(eq("gateway.source.base-url"), any())).thenReturn("https://live-source.example.com");
        when(settings.get(eq("gateway.source.response-save-uri"), any())).thenReturn("/api/save");
        when(sourceAuthService.authHeaders()).thenReturn(Map.of("Authorization", "Basic xxx"));
        when(tenantMappingService.toExternal(anyLong())).thenReturn(Map.of("company_id", 100L));

        BiruniClient client = new BiruniClient(builder.build(), props, tenantMappingService, sourceAuthService, settings);
        boolean saved = client.saveResponse(ResponseSaveRequest.builder()
                .tenantId(100L)
                .requestId(1L)
                .build());

        assertTrue(saved);
        server.verify();
    }
}
