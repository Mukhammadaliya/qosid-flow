package uz.greenwhite.gateway.exception;

/**
 * Thrown when an inbound request body exceeds {@code gateway.proxy.max-body-size}.
 * Mapped to HTTP 413 by {@link GlobalExceptionHandler}.
 *
 * <p>Distinct from Spring's {@code MaxUploadSizeExceededException} (which covers the
 * multipart branch via {@code spring.servlet.multipart.*}) so the two limits stay
 * independently tunable and independently diagnosable in the logs.</p>
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
