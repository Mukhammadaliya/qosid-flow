package uz.greenwhite.gateway.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.model.ResponseSaveRequest;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.RequestSourceClient;
import uz.greenwhite.gateway.source.ResponseSinkClient;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BiruniClient implements RequestSourceClient, ResponseSinkClient {

    private final RestClient restClient;
    private final GatewayProperties properties;
    private final TenantMappingService tenantMappingService;
    private final SourceAuthService sourceAuthService;
    private final SettingsService settingsService;

    @Autowired
    public BiruniClient(GatewayProperties properties, TenantMappingService tenantMappingService,
                         SourceAuthService sourceAuthService, SettingsService settingsService) {
        this(buildRestClient(properties), properties, tenantMappingService, sourceAuthService, settingsService);
    }

    /**
     * Test seam: allows binding a {@link org.springframework.test.web.client.MockRestServiceServer}
     * to the RestClient. Production wiring goes through the {@code @Autowired} constructor.
     */
    BiruniClient(RestClient restClient, GatewayProperties properties, TenantMappingService tenantMappingService,
                 SourceAuthService sourceAuthService, SettingsService settingsService) {
        this.restClient = restClient;
        this.properties = properties;
        this.tenantMappingService = tenantMappingService;
        this.sourceAuthService = sourceAuthService;
        this.settingsService = settingsService;
    }

    private static RestClient buildRestClient(GatewayProperties properties) {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectionTimeout()))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.getConnectionTimeout()));

        // NOTE: no .baseUrl(...) here — the base URL is read live from SettingsService
        // on every call so admin edits apply without restart.
        return RestClient.builder()
                .requestFactory(factory)
                .messageConverters(converters -> {
                    MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
                    converter.setSupportedMediaTypes(Arrays.asList(
                            MediaType.APPLICATION_JSON,
                            MediaType.TEXT_PLAIN
                    ));
                    converters.add(converter);
                })
                .build();
    }

    // ==================== RequestSourceClient ====================

    /**
     * Pull pending requests from the data source (status = 'N').
     * Sends HTTP GET to the configured pull URI with Basic Auth.
     *
     * @return list of request messages ready for processing
     */
    @Override
    public List<RequestMessage> pullRequests() {
        try {
            String base = settingsService.get("gateway.source.base-url", properties.getBaseUrl());
            String pullUri = settingsService.get("gateway.source.request-pull-uri", properties.getRequestPullUri());
            log.debug("Pulling requests from: {}{}", base, pullUri);

            Map<String, String> authHeaders = sourceAuthService.authHeaders();
            List<JsonNode> raw = restClient.get()
                    .uri(base + pullUri)
                    .headers(h -> authHeaders.forEach(h::add))
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<JsonNode>>() {})
                    .getBody();

            if (raw == null || raw.isEmpty()) {
                return List.of();
            }

            List<RequestMessage> requests = raw.stream()
                    .map(tenantMappingService::toInternal)
                    .toList();

            log.info("Pulled {} requests from data source", requests.size());
            return requests;

        } catch (Exception e) {
            log.error("Error pulling requests from data source: {}", e.getMessage(), e);
            throw new RuntimeException("Request pull failed: " + e.getMessage(), e);
        }
    }

    // ==================== ResponseSinkClient ====================

    /**
     * Save HTTP response back to the data source.
     * Sends HTTP POST to the configured save URI with Basic Auth.
     *
     * @param request the response data to save
     * @return true if saved successfully
     */
    @Override
    public boolean saveResponse(ResponseSaveRequest request) {
        try {
            log.debug("Saving response for: {}:{}", request.getTenantId(), request.getRequestId());

            Map<String, Object> body = new LinkedHashMap<>(
                    tenantMappingService.toExternal(request.getTenantId()));
            body.put("request_id", request.getRequestId());
            body.put("response", request.getResponse());
            body.put("error_message", request.getErrorMessage());

            String saveBase = settingsService.get("gateway.source.base-url", properties.getBaseUrl());
            String saveUri = settingsService.get("gateway.source.response-save-uri", properties.getResponseSaveUri());

            Map<String, String> authHeaders = sourceAuthService.authHeaders();
            var response = restClient.post()
                    .uri(saveBase + saveUri)
                    .headers(h -> authHeaders.forEach(h::add))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(body))
                    .exchange((req, resp) -> {
                        int status = resp.getStatusCode().value();
                        log.debug("Save response status: {}", status);
                        return status >= 200 && status < 300;
                    });

            if (response) {
                log.info("Response saved successfully: {}:{}", request.getTenantId(), request.getRequestId());
            }

            return response;

        } catch (Exception e) {
            log.error("Error saving response to data source: {}", e.getMessage(), e);
            return false;
        }
    }
}