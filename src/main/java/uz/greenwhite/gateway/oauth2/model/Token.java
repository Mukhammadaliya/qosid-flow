package uz.greenwhite.gateway.oauth2.model;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Token(String accessToken,
                    long expiresIn,
                    String tokenType,
                    String refreshToken,
                    long createdAt) implements Serializable {

    private static final long MARGIN_IN_MILLIS = 15 * 1000;
    private static final double REFRESH_AHEAD_RATIO = 0.8;

    public Token(String accessToken, long expiresIn, String tokenType, String refreshToken) {
        this(accessToken, expiresIn, tokenType, refreshToken, System.currentTimeMillis());
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > (createdAt + expiresIn - MARGIN_IN_MILLIS);
    }

    /**
     * True once we have crossed 80% of the token's lifetime. Callers should keep
     * using the still-valid token but trigger an asynchronous refresh so the next
     * request after expiry never has to wait for a network round trip.
     */
    public boolean isDueForRefresh() {
        return System.currentTimeMillis() > (createdAt + (long) (expiresIn * REFRESH_AHEAD_RATIO));
    }

    public String getAuthorizationHeader() {
        String header;
        if (tokenType != null && tokenType.equalsIgnoreCase("bearer")) {
            header = "Bearer " + accessToken;
        } else {
            header = tokenType + " " + accessToken;
        }
        return header.trim();
    }
}