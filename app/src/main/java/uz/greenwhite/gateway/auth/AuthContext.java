package uz.greenwhite.gateway.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Snapshot of an inbound HTTP request handed to {@link AuthorizationProvider}
 * implementations. Contains everything the provider needs to decide allow/deny
 * without exposing servlet APIs to the auth layer.
 */
@Data
@Builder
@AllArgsConstructor
public class AuthContext {

    /** Request URI (path), e.g. {@code /qosid/api/v1/queue/foo}. */
    private String uri;

    /** HTTP method (GET, POST, ...). */
    private String method;

    /** Inbound request headers, lowercased keys recommended by callers. */
    private Map<String, String> headers;

    /** Originating client IP, after proxy/X-Forwarded-For resolution. */
    private String clientIp;

    /** Raw query string (without leading {@code ?}). */
    private String queryParams;
}
