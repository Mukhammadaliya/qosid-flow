package uz.greenwhite.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.gateway.api.model.StatusResponse;
import uz.greenwhite.gateway.model.RequestState;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseMetadata;
import uz.greenwhite.gateway.storage.ResponseStorageService;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Status / result endpoints for queued requests.
 *
 * <ul>
 *   <li>{@code GET /qosid/api/v1/requests/{id}/status} — current status, with
 *   the result inlined for small JSON/text payloads.</li>
 *   <li>{@code GET /qosid/api/v1/requests/{id}/result} — the full response body
 *   from RocksDB with the original Content-Type and HTTP status.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/qosid/api/v1/requests")
@RequiredArgsConstructor
public class StatusController {

    private final RequestStateService requestStateService;
    private final ResponseStorageService responseStorageService;
    private final ObjectMapper objectMapper;

    @Value("${gateway.response.threshold-size:1024}")
    private int responseThresholdSize;

    // ==================== STATUS ====================

    @GetMapping("/{id}/status")
    public ResponseEntity<StatusResponse> getStatus(@PathVariable String id) {
        Optional<RequestState> stateOpt = requestStateService.getState(id);
        if (stateOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RequestState state = stateOpt.get();
        StatusResponse.StatusResponseBuilder builder = StatusResponse.builder()
                .requestId(id)
                .status(state.getStatus().name());

        if (state.getStatus() == RequestStatus.COMPLETED && state.isHasResult()) {
            attachResult(id, builder);
        } else if (state.getStatus() == RequestStatus.FAILED) {
            builder.errorMessage(state.getLastError());
            if (state.getErrorSource() != null) {
                builder.errorSource(state.getErrorSource().name());
            }
        }

        return ResponseEntity.ok(builder.build());
    }

    /**
     * Either inline the body (small JSON/text) or hand back a {@code resultUrl}.
     */
    private void attachResult(String id, StatusResponse.StatusResponseBuilder builder) {
        ResponseMetadata meta = responseStorageService.getResponseMetadata(id);
        if (meta == null) {
            return;
        }

        byte[] body = responseStorageService.getResponseBody(id);
        if (body == null || body.length >= responseThresholdSize) {
            builder.resultUrl(resultUrl(id));
            return;
        }

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(meta.getContentType());
        } catch (Exception e) {
            log.warn("Unparseable content-type {} for {}, falling back to resultUrl", meta.getContentType(), id);
            builder.resultUrl(resultUrl(id));
            return;
        }

        if (mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            try {
                builder.result(objectMapper.readTree(body));
            } catch (Exception e) {
                log.warn("Failed to parse JSON body for {}, falling back to resultUrl: {}", id, e.getMessage());
                builder.resultUrl(resultUrl(id));
            }
        } else if (mediaType.isCompatibleWith(MediaType.TEXT_PLAIN)) {
            builder.result(new String(body, StandardCharsets.UTF_8));
        } else {
            builder.resultUrl(resultUrl(id));
        }
    }

    private static String resultUrl(String id) {
        return "/qosid/api/v1/requests/" + id + "/result";
    }

    // ==================== RESULT ====================

    @GetMapping("/{id}/result")
    public ResponseEntity<byte[]> getResult(@PathVariable String id) {
        ResponseMetadata meta = responseStorageService.getResponseMetadata(id);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] body = responseStorageService.getResponseBody(id);
        if (body == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.status(meta.getHttpStatus())
                .contentType(MediaType.parseMediaType(meta.getContentType()))
                .contentLength(body.length)
                .body(body);
    }
}
