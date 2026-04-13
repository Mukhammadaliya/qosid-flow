package uz.greenwhite.gateway.auth;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Outcome of an {@link AuthorizationProvider#authorize(AuthContext)} call.
 * Construct via the static factories — the all-args constructor is private.
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthResult {

    private boolean allowed;

    /** Reason for denial; {@code null} when allowed. */
    private String reason;

    /** HTTP status to return on denial; 200 when allowed. */
    private int httpStatus;

    public static AuthResult allowed() {
        return new AuthResult(true, null, 200);
    }

    public static AuthResult denied(String reason) {
        return new AuthResult(false, reason, 403);
    }

    public static AuthResult denied(String reason, int httpStatus) {
        return new AuthResult(false, reason, httpStatus);
    }
}
