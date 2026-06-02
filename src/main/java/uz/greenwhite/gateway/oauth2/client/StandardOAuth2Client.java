package uz.greenwhite.gateway.oauth2.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import uz.greenwhite.gateway.oauth2.GrantType;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;

/**
 * Standard RFC 6749 OAuth2 client for the {@code client_credentials} grant.
 * <p>
 * Unlike {@link BiruniOAuth2Client} (which posts a JSON body to Biruni's proprietary
 * token endpoint), this client sends an {@code application/x-www-form-urlencoded} body
 * as required by spec-compliant providers such as Regos ({@code auth.regos.uz}).
 * <p>
 * The {@code client_credentials} flow has no refresh token, so {@link #refreshAccessToken}
 * simply acquires a fresh token, and the token response is parsed null-safely
 * (no {@code refresh_token} is expected).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StandardOAuth2Client implements OAuth2Client {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final String NAME = "standard";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Token getAccessToken(ProviderProperties properties) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GrantType.CLIENT_CREDENTIALS.getValue());
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        if (properties.getScope() != null && !properties.getScope().isBlank()) {
            form.add("scope", properties.getScope());
        }

        return sendTokenRequest(properties.getTokenUrl(), form);
    }

    @Override
    public Token refreshAccessToken(ProviderProperties properties, Token token) {
        // client_credentials grant issues no refresh token — just acquire a new one.
        return getAccessToken(properties);
    }

    private Token sendTokenRequest(String tokenUrl, MultiValueMap<String, String> form) {
        try {
            String responseBody = webClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseToken(responseBody);

        } catch (Exception e) {
            log.error("Failed to get OAuth2 token from {}: {}", tokenUrl, e.getMessage());
            throw new RuntimeException("Failed to get OAuth2 token", e);
        }
    }

    private Token parseToken(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);

            JsonNode tokenType = json.get("token_type");
            JsonNode refreshToken = json.get("refresh_token");

            return new Token(
                    json.get("access_token").asText(),
                    json.get("expires_in").asLong() * 1000,
                    tokenType != null ? tokenType.asText() : "Bearer",
                    refreshToken != null ? refreshToken.asText() : null
            );
        } catch (Exception e) {
            log.error("Failed to parse OAuth2 token response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse OAuth2 token", e);
        }
    }
}