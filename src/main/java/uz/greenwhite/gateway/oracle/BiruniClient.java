package uz.greenwhite.gateway.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.model.ResponseSaveRequest;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.source.RequestSourceClient;
import uz.greenwhite.gateway.source.ResponseSinkClient;
import uz.greenwhite.gateway.tenant.TenantMappingService;
import uz.greenwhite.gateway.util.AuthUtil;

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

    public BiruniClient(GatewayProperties properties, TenantMappingService tenantMappingService) {
        this.properties = properties;
        this.tenantMappingService = tenantMappingService;

        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectionTimeout()))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.getConnectionTimeout()));

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
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
            log.debug("Pulling requests from: {}{}", properties.getBaseUrl(), properties.getRequestPullUri());

            List<JsonNode> raw = restClient.get()
                    .uri(properties.getRequestPullUri())
                    .header(HttpHeaders.AUTHORIZATION,
                            AuthUtil.generateBasicAuth(properties.getUsername(), properties.getPassword()))
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

            var response = restClient.post()
                    .uri(properties.getResponseSaveUri())
                    .header(HttpHeaders.AUTHORIZATION,
                            AuthUtil.generateBasicAuth(properties.getUsername(), properties.getPassword()))
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