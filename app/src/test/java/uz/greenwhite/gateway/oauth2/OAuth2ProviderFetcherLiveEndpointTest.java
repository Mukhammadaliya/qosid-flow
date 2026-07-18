package uz.greenwhite.gateway.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the OAuth2 provider-data fetch targets the LIVE URI read from
 * {@link SettingsService} at request time, not the startup-bound
 * {@link GatewayProperties#getOauth2ProviderUri()} value — so admin edits of
 * {@code gateway.source.oauth2-provider-uri} apply without a restart (mirrors
 * {@code BiruniClientLiveEndpointTest}).
 */
@ExtendWith(MockitoExtension.class)
class OAuth2ProviderFetcherLiveEndpointTest {

    @Mock SettingsService settings;
    @Mock GatewayProperties props;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock TenantMappingService tenantMappingService;

    @Test
    void fetchUsesLiveProviderUri() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://live-source.example.com/live/provider-data"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("{\"tokenUrl\":\"https://idp/token\",\"clientId\":\"cid\"}",
                      MediaType.APPLICATION_JSON));

        // Redis cache miss → HTTP fallback path.
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);
        // Startup-bound yaml value points elsewhere — it is only the fallback and must NOT win.
        when(props.getOauth2ProviderUri()).thenReturn("/yaml/wrong-provider-data");
        when(settings.get(eq("gateway.source.oauth2-provider-uri"), any()))
                .thenReturn("https://live-source.example.com/live/provider-data");
        when(props.getUsername()).thenReturn("u");
        when(props.getPassword()).thenReturn("p");
        when(tenantMappingService.toExternal(anyLong())).thenReturn(Map.of("company_id", 100L));

        OAuth2ProviderFetcher fetcher = new OAuth2ProviderFetcher(
                builder.build(), props, redisTemplate, new ObjectMapper(), tenantMappingService, settings);
        ProviderProperties result = fetcher.fetch("smartup", 100L);

        assertNotNull(result);
        assertEquals("https://idp/token", result.getTokenUrl());
        assertEquals("cid", result.getClientId());
        server.verify();
    }
}
