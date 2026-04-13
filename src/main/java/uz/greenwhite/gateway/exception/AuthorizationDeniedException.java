package uz.greenwhite.gateway.exception;

import lombok.Getter;

/**
 * Thrown by {@link uz.greenwhite.gateway.auth.AuthorizationProvider} implementations
 * when a request is rejected. Carries the HTTP status to return — defaults to 403,
 * but the auth provider can override (e.g., 401 for missing credentials).
 */
@Getter
public class AuthorizationDeniedException extends RuntimeException {

    private final int httpStatus;

    public AuthorizationDeniedException(String message) {
        this(message, 403);
    }

    public AuthorizationDeniedException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }
}
