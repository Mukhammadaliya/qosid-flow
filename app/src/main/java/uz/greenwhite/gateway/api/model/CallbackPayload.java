package uz.greenwhite.gateway.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON body POSTed to a caller-supplied {@code X-Callback-Url} when a request
 * finishes processing. Mirrors the shape of {@link StatusResponse} with extra
 * upstream context ({@code httpStatus}, {@code contentType}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CallbackPayload {

    private String requestId;

    /** {@code COMPLETED} or {@code FAILED}. */
    private String status;

    /** HTTP status returned by the upstream API. */
    private int httpStatus;

    private String contentType;

    /** Inline result body for small JSON/text payloads. */
    private Object result;

    /** Pointer to the full result when too large to inline. */
    private String resultUrl;

    /** Set on {@code FAILED}. */
    private String errorMessage;
}
