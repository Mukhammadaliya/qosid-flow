package uz.greenwhite.gateway.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Body returned by {@code GET /qosid/api/v1/requests/{id}/status}.
 * Inline {@code result} is included for small JSON/text bodies; otherwise
 * {@code resultUrl} points to the {@code /result} endpoint for the full payload.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusResponse {

    private String requestId;

    /** {@code PROCESSING}, {@code COMPLETED}, or {@code FAILED}. */
    private String status;

    /** Inline result body — present only when below the configured threshold and JSON/text. */
    private Object result;

    /** URL pointing at the full result body when too large to inline. */
    private String resultUrl;

    /** Set on {@code FAILED}. */
    private String errorMessage;

    /** Set on {@code FAILED} — e.g. {@code HTTP}, {@code SYSTEM}, {@code CIRCUIT_BREAKER}. */
    private String errorSource;
}
