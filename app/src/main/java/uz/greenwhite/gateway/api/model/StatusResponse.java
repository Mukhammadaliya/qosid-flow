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

    /**
     * HTTP status the upstream API answered with, straight from the stored response metadata.
     * Boxed and omitted while unknown (still in flight, or no body was ever stored) — a literal
     * {@code 0} would read as a real answer. Present on {@code FAILED} too: failed upstream
     * bodies are stored as well, which is what makes "502 vs 200" visible without fetching the
     * whole body from {@code /result}.
     */
    private Integer httpStatus;

    /** Inline result body — present only when below the configured threshold and JSON/text. */
    private Object result;

    /** URL pointing at the full result body when too large to inline. */
    private String resultUrl;

    /** Set on {@code FAILED}. */
    private String errorMessage;

    /** Set on {@code FAILED} — e.g. {@code HTTP}, {@code SYSTEM}, {@code CIRCUIT_BREAKER}. */
    private String errorSource;
}
