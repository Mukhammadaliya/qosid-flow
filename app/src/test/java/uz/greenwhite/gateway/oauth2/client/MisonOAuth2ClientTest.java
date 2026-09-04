package uz.greenwhite.gateway.oauth2.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import uz.greenwhite.gateway.oauth2.model.ProviderProperties;
import uz.greenwhite.gateway.oauth2.model.Token;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MisonOAuth2ClientTest {

    private static final String TOKEN_JSON =
            "{\"access_token\":\"tok-123\",\"expires_in\":1944788,\"token_type\":\"Bearer\"}";

    /**
     * Deliberately not a real Mison host. The client must post to whatever tokenUrl the
     * provider carries — the host arrives from Oracle at runtime (MISON:request_host), so
     * pinning a real address here would only invite an accidental live call.
     */
    private static final String TOKEN_URL = "https://mison.invalid/api/integrations/v1/auth/token";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebClient stubWebClient(AtomicReference<ClientRequest> captured, String responseBody) {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(responseBody)
                            .build());
                })
                .build();
    }

    private ProviderProperties properties() {
        return ProviderProperties.builder()
                .type("mison")
                .tokenUrl(TOKEN_URL)
                .clientId("secret-api-key")
                .build();
    }

    @Test
    void nameIsMison() {
        MisonOAuth2Client client = new MisonOAuth2Client(WebClient.builder().build(), objectMapper);
        assertThat(client.getName()).isEqualTo("mison");
    }

    @Test
    void getAccessTokenParsesTokenAndConvertsExpiryToMillis() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        MisonOAuth2Client client = new MisonOAuth2Client(stubWebClient(captured, TOKEN_JSON), objectMapper);

        Token token = client.getAccessToken(properties());

        assertThat(token.accessToken()).isEqualTo("tok-123");
        assertThat(token.expiresIn()).isEqualTo(1944788L * 1000);
        assertThat(token.tokenType()).isEqualTo("Bearer");
        assertThat(token.refreshToken()).isNull();
    }

    @Test
    void getAccessTokenPostsToTokenUrlAsJson() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        MisonOAuth2Client client = new MisonOAuth2Client(stubWebClient(captured, TOKEN_JSON), objectMapper);

        client.getAccessToken(properties());

        assertThat(captured.get().url().toString()).isEqualTo(TOKEN_URL);
        assertThat(captured.get().headers().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void defaultsTokenTypeToBearerWhenAbsent() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        MisonOAuth2Client client = new MisonOAuth2Client(
                stubWebClient(captured, "{\"access_token\":\"a\",\"expires_in\":60}"), objectMapper);

        assertThat(client.getAccessToken(properties()).tokenType()).isEqualTo("Bearer");
    }

    @Test
    void throwsWhenTokenResponseIsMissingAccessToken() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        MisonOAuth2Client client = new MisonOAuth2Client(
                stubWebClient(captured, "{\"error\":\"invalid api key\"}"), objectMapper);

        assertThatThrownBy(() -> client.getAccessToken(properties()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void refreshAcquiresAFreshToken() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        MisonOAuth2Client client = new MisonOAuth2Client(stubWebClient(captured, TOKEN_JSON), objectMapper);

        Token refreshed = client.refreshAccessToken(properties(),
                new Token("old", 1000, "Bearer", null));

        assertThat(refreshed.accessToken()).isEqualTo("tok-123");
    }
}
