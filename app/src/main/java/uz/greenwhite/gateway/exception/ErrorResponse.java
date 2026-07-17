package uz.greenwhite.gateway.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Standard JSON envelope returned by {@link GlobalExceptionHandler}
 * for any unhandled or domain exception thrown from the API layer.
 */
@Data
@Builder
@AllArgsConstructor
public class ErrorResponse {

    /** Short human-readable category, e.g. {@code "Unauthorized"}. */
    private String error;

    /** Detailed reason; safe to surface to API callers. */
    private String message;

    /** HTTP status code mirrored in the response body for convenience. */
    private int status;

    /** Server-side time the error response was generated. */
    private LocalDateTime timestamp;
}
