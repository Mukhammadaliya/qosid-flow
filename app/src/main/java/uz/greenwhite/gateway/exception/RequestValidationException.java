package uz.greenwhite.gateway.exception;

/**
 * Thrown by API-layer validators when the incoming request is structurally or
 * semantically invalid (missing required header, unsupported content-type, etc.).
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message);
    }
}
