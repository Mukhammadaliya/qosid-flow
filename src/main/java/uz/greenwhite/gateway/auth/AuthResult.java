package uz.greenwhite.gateway.auth;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Outcome of an authorize() call. Carries the resolved tenant (nullable) so the
 * proxy can tag the request without trusting a client-supplied value. Construct
 * via the static factories.
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthResult {

    private boolean allowed;
    private String reason;
    private int httpStatus;
    /** Resolved tenant id; null when the provider does not supply one (e.g. NoOp). */
    private Long tenantId;
    /** Resolved extra-tenant; null when not supplied. */
    private String extraTenantId;

    public static AuthResult allowed() {
        return new AuthResult(true, null, 200, null, null);
    }

    public static AuthResult allowed(Long tenantId, String extraTenantId) {
        return new AuthResult(true, null, 200, tenantId, extraTenantId);
    }

    public static AuthResult denied(String reason) {
        return new AuthResult(false, reason, 403, null, null);
    }

    public static AuthResult denied(String reason, int httpStatus) {
        return new AuthResult(false, reason, httpStatus, null, null);
    }
}
