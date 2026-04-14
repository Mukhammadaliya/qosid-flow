package uz.greenwhite.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.gateway.api.model.ProxyResponse;
import uz.greenwhite.gateway.auth.AuthContext;
import uz.greenwhite.gateway.auth.AuthResult;
import uz.greenwhite.gateway.auth.AuthorizationProvider;
import uz.greenwhite.gateway.exception.AuthorizationDeniedException;
import uz.greenwhite.gateway.exception.RequestValidationException;
import uz.greenwhite.gateway.config.ProxyProperties;
import uz.greenwhite.gateway.file.FileService;
import uz.greenwhite.gateway.file.ServletFileUploadEntity;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.model.kafka.RequestMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Transparent REST proxy. Accepts any method on {@code /qosid/queue/**},
 * runs authorization, then enqueues a {@link RequestMessage} for downstream
 * processing. Returns 202 ACCEPTED with a polling URL.
 *
 * <p>Inbound API requests are tagged {@code source="API"}. Because the existing
 * {@link RequestMessage#getCompositeId()} returns {@code companyId:requestId},
 * API requests are assigned {@code companyId=0L} and a derived non-negative
 * {@code long} from a fresh UUID. This is a known shortcut documented in the
 * plan; a dedicated {@code compositeId} field on {@code RequestMessage} would
 * be the cleaner long-term fix.</p>
 */
@Slf4j
@RestController
@RequestMapping("${gateway.proxy.prefix}")
@RequiredArgsConstructor
public class ProxyController {

    private static final long API_COMPANY_ID = 0L;
    private static final String CALLBACK_HEADER = "X-Callback-Url";
    private static final String BIRUNI_UPLOAD_HEADER = "BiruniUpload";

    private final AuthorizationProvider authorizationProvider;
    private final RequestProducer requestProducer;
    private final FileService fileService;
    private final ProxyProperties proxyProperties;

    @RequestMapping(
            value = "/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                      RequestMethod.DELETE, RequestMethod.PATCH}
    )
    public ResponseEntity<ProxyResponse> handleRequest(HttpServletRequest request) throws IOException {
        Map<String, String> headers = extractHeaders(request);
        String clientIp = getClientIp(request);

        // 1. Authorization
        AuthContext authContext = AuthContext.builder()
                .uri(request.getRequestURI())
                .method(request.getMethod())
                .headers(headers)
                .clientIp(clientIp)
                .queryParams(request.getQueryString())
                .build();

        AuthResult authResult = authorizationProvider.authorize(authContext);
        if (!authResult.isAllowed()) {
            throw new AuthorizationDeniedException(authResult.getReason(), authResult.getHttpStatus());
        }

        // 2. Strip the proxy prefix to get the upstream URI
        String targetUri = request.getRequestURI().replaceFirst(proxyProperties.getPrefix(), "");

        // 3. Allocate IDs (see class-level note about the shortcut)
        UUID uuid = UUID.randomUUID();
        long requestId = uuid.getMostSignificantBits() & Long.MAX_VALUE;

        // 4. BiruniUpload handling — either parse multipart + upload to S3, or read raw body
        String biruniUpload = request.getHeader(BIRUNI_UPLOAD_HEADER);
        List<String> fileReferences = null;
        String bodyStr;

        if ("param".equals(biruniUpload)) {
            ServletFileUploadEntity entity = new ServletFileUploadEntity(request, "param");
            fileReferences = fileService.uploadFiles(entity.getFiles());
            bodyStr = entity.getUpdatedParam();
        } else if ("alone".equals(biruniUpload)) {
            ServletFileUploadEntity entity = new ServletFileUploadEntity(request, "alone");
            fileReferences = fileService.uploadFiles(entity.getFiles());
            bodyStr = null;
        } else if (biruniUpload != null) {
            throw new RequestValidationException("Invalid value for BiruniUpload header: " + biruniUpload);
        } else {
            bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        // 5. Build the Kafka message
        RequestMessage message = RequestMessage.builder()
                .companyId(API_COMPANY_ID)
                .requestId(requestId)
                .source("API")
                .uri(targetUri)
                .method(request.getMethod())
                .headers(headers)
                .body(bodyStr)
                .params(request.getQueryString())
                .clientIp(clientIp)
                .contentType(request.getContentType())
                .callbackUrl(request.getHeader(CALLBACK_HEADER))
                .fileReferences(fileReferences)
                .build();

        String compositeId = message.getCompositeId();

        // 6. Dispatch to Kafka, blocking up to 10s for the broker to ack
        try {
            requestProducer.sendRequest(message).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to enqueue API request {}: {}", compositeId, e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue request: " + e.getMessage(), e);
        }

        // 7. Synchronous handshake response
        ProxyResponse response = ProxyResponse.builder()
                .requestId(compositeId)
                .status("ACCEPTED")
                .statusUrl(proxyProperties.getContextPath() + "/api/v1/requests/" + compositeId + "/status")
                .build();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    // ==================== HELPERS ====================

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return Collections.emptyMap();
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    /**
     * Resolve client IP honoring proxy headers. Falls back to the socket address.
     */
    private static String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Take the leftmost address — that's the original client
            int comma = forwardedFor.indexOf(',');
            return comma > 0 ? forwardedFor.substring(0, comma).trim() : forwardedFor.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
