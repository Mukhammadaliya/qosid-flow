package uz.greenwhite.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import uz.greenwhite.gateway.config.ApiProperties;
import uz.greenwhite.gateway.config.ProxyProperties;
import uz.greenwhite.gateway.file.FileService;
import uz.greenwhite.gateway.file.ServletFileUploadEntity;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.model.kafka.RequestMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Transparent REST proxy. Accepts any method on {@code /qosid/api/v1/queue/**},
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
    private final ApiProperties apiProperties;

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

        // 4. BiruniUpload handling — parse multipart, try S3, fallback to Biruni DB
        String biruniUpload = request.getHeader(BIRUNI_UPLOAD_HEADER);
        List<String> fileReferences = null;
        String bodyStr;
        String contentType;
        String biruniUploadMode = null;
        List<RequestMessage.FilePart> fileParts = null;

        if ("param".equals(biruniUpload) || "alone".equals(biruniUpload)) {
            ServletFileUploadEntity entity = new ServletFileUploadEntity(request, biruniUpload);
            boolean s3Success = fileService.tryUploadFiles(entity.getFiles());

            if (s3Success) {
                // S3 OK → send as JSON with "files" metadata header
                fileReferences = entity.getFiles().stream()
                        .map(ServletFileUploadEntity.FileData::getSha).toList();
                bodyStr = entity.getUpdatedParam();
                String filesJson = buildFilesHeaderJson(entity.getFiles());

                headers.keySet().removeIf(h ->
                        h.equalsIgnoreCase("content-type")
                        || h.equalsIgnoreCase("content-length")
                        || h.equalsIgnoreCase(BIRUNI_UPLOAD_HEADER));
                headers.put("files", filesJson);
                contentType = MediaType.APPLICATION_JSON_VALUE;
            } else {
                // S3 FAILED → fallback: send multipart to Biruni for DB storage
                log.warn("S3 upload failed, falling back to Biruni DB storage");
                bodyStr = entity.getUpdatedParam();
                biruniUploadMode = biruniUpload;
                fileParts = new ArrayList<>();
                for (int i = 0; i < entity.getFiles().size(); i++) {
                    ServletFileUploadEntity.FileData f = entity.getFiles().get(i);
                    fileParts.add(RequestMessage.FilePart.builder()
                            .fieldName("file[" + i + "]")
                            .fileName(f.getFileName())
                            .contentType(f.getContentType())
                            .data(f.getData())
                            .build());
                }
                // Keep original multipart headers for Biruni
                headers.keySet().removeIf(h ->
                        h.equalsIgnoreCase("content-type")
                        || h.equalsIgnoreCase("content-length"));
                contentType = null;
            }
        } else if (biruniUpload != null) {
            throw new RequestValidationException("Invalid value for BiruniUpload header: " + biruniUpload);
        } else {
            bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            contentType = request.getContentType();
        }

        // 6. Build the Kafka message
        RequestMessage message = RequestMessage.builder()
                .companyId(API_COMPANY_ID)
                .requestId(requestId)
                .source("API")
                .baseUrl(apiProperties.getBaseUrl())
                .uri(targetUri)
                .method(request.getMethod())
                .headers(headers)
                .body(bodyStr)
                .params(request.getQueryString())
                .clientIp(clientIp)
                .contentType(contentType)
                .callbackUrl(request.getHeader(CALLBACK_HEADER))
                .fileReferences(fileReferences)
                .biruniUploadMode(biruniUploadMode)
                .fileParts(fileParts)
                .build();

        String compositeId = message.getCompositeId();

        // 7. Dispatch to Kafka, blocking up to 10s for the broker to ack
        try {
            requestProducer.sendApiRequest(message).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to enqueue API request {}: {}", compositeId, e.getMessage(), e);
            throw new RuntimeException("Failed to enqueue request: " + e.getMessage(), e);
        }

        // 8. Synchronous handshake response
        ProxyResponse response = ProxyResponse.builder()
                .requestId(compositeId)
                .status("ACCEPTED")
                .statusUrl(proxyProperties.getContextPath() + "/api/v1/requests/" + compositeId + "/status")
                .build();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    // ==================== HELPERS ====================

    /**
     * Build a JSON array of file metadata for Biruni's {@code OracleHeader.getFiles()}.
     * Format: {@code [{"file_name":"x","content_type":"y","file_size":"z","sha":"h","store_kind":"S","bucket":"PERMANENT"}]}
     */
    private static String buildFilesHeaderJson(List<ServletFileUploadEntity.FileData> files) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(",");
            ServletFileUploadEntity.FileData f = files.get(i);
            sb.append("{")
              .append("\"file_name\":\"").append(escapeJson(f.getFileName())).append("\",")
              .append("\"content_type\":\"").append(escapeJson(f.getContentType())).append("\",")
              .append("\"file_size\":\"").append(f.getSize()).append("\",")
              .append("\"sha\":\"").append(f.getSha()).append("\",")
              .append("\"store_kind\":\"S\",")
              .append("\"bucket\":\"PERMANENT\"")
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Headers stripped from the forwarded request. Standard RFC 7230 hop-by-hop
     * headers plus {@code accept-encoding}: we intentionally force upstream
     * responses to be uncompressed because intermediaries (Cloudflare in HTTP/1.1)
     * have been observed returning gzip that reactor-netty can only partially
     * decode, leaving the stored body as unreadable raw deflate. The outbound
     * response from qosid-flow is still compressed by its own Tomcat based on
     * the client's own Accept-Encoding.
     */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "host", "connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade",
            "accept-encoding");

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return Collections.emptyMap();
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, request.getHeader(name));
            }
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
