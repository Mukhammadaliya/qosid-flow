package uz.greenwhite.gateway.model.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorSource {

    HTTP("HTTP", "External API error"),
    SYSTEM("SYSTEM", "Internal system error"),
    CIRCUIT_BREAKER("CIRCUIT_BREAKER", "Circuit breaker rejected"),
    OAUTH2("OAUTH2", "OAuth2 token error");

    private final String code;
    private final String description;

    /**
     * Parse errorSource string to enum safely.
     * Returns HTTP as default for null or unknown values.
     */
    public static ErrorSource fromString(String errorSource) {
        if (errorSource == null) return HTTP;
        try {
            return ErrorSource.valueOf(errorSource);
        } catch (IllegalArgumentException e) {
            return HTTP;
        }
    }
}