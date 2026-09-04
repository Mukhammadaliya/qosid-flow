package uz.greenwhite.gateway.oauth2.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;

import java.util.Map;

/**
 * OAuth2 client for MISON POS ({@code backend.mison.uz}).
 * <p>
 * Mison's token endpoint is neither RFC 6749 nor Biruni-shaped: it accepts a JSON body
 * containing a single {@code api_key} field and returns a long-lived bearer token
 * (~22.5 days) with no refresh token. The API key is carried in
 * {@link ProviderProperties#getClientId()} because {@code OAuth2ProviderFetcher}
 * discards providers whose {@code clientId} is null.
 * <p>
 * Like {@link StandardOAuth2Client}, the response is parsed null-safely — no
 * {@code refresh_token} is expected — and {@link #refreshAccessToken} simply
 * acquires a fresh token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MisonOAuth2Client implements OAuth2Client {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final String NAME = "mison";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Token getAccessToken(ProviderProperties properties) {
        return sendTokenRequest(properties.getTokenUrl(),
                Map.of("api_key", properties.getClientId()));
    }

    @Override
    public Token refreshAccessToken(ProviderProperties properties, Token token) {
        // Mison issues no refresh token — just acquire a new one.
        return getAccessToken(properties);
    }

    private Token sendTokenRequest(String tokenUrl, Map<String, String> body) {
        try {
            String responseBody = webClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseToken(responseBody);

        } catch (Exception e) {
            log.error("Failed to get Mison OAuth2 token from {}: {}", tokenUrl, e.getMessage());
            throw new RuntimeException("Failed to get OAuth2 token", e);
        }
    }

    private Token parseToken(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);

            JsonNode accessToken = json.get("access_token");
            JsonNode expiresIn = json.get("expires_in");
            if (accessToken == null || expiresIn == null) {
                // Provider returned 2xx but an error/unexpected payload — log the raw body for diagnosis.
                throw new IllegalStateException(
                        "Token response missing access_token/expires_in: " + responseBody);
            }

            JsonNode tokenType = json.get("token_type");

            return new Token(
                    accessToken.asText(),
                    expiresIn.asLong() * 1000,
                    tokenType != null ? tokenType.asText() : "Bearer",
                    null
            );
        } catch (Exception e) {
            log.error("Failed to parse Mison OAuth2 token response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse OAuth2 token", e);
        }
    }
}
