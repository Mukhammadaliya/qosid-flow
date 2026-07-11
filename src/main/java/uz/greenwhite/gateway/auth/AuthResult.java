package uz.greenwhite.gateway.auth;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Outcome of an authorize() call. Carries the resolved tenant (nullable) so the
 * proxy can tag the request without trusting a client-supplied value. Immutable:
 * all fields are final and there are no setters — construct via the static factories.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthResult {

    private final boolean allowed;
    private final String reason;
    private final int httpStatus;
    /** Resolved tenant id; null when the provider does not supply one (e.g. NoOp). */
    private final Long tenantId;
    /** Resolved extra-tenant; null when not supplied. */
    private final String extraTenantId;

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
