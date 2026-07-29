package uz.greenwhite.gateway.exception;

/**
 * Thrown when authentication itself fails (invalid token, expired credential,
 * bad signature). Mapped to HTTP 401 by {@link GlobalExceptionHandler}.
 *
 * <p>This is a project-local replacement for Spring Security's
 * {@code AuthenticationException}, which is intentionally not on the classpath.</p>
 */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
