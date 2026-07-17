package uz.greenwhite.gateway.oauth2.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.oauth2.model.Token;

import static org.junit.jupiter.api.Assertions.*;

class BiruniOAuth2ClientTest {

    private final BiruniOAuth2Client client = new BiruniOAuth2Client(null, new ObjectMapper());

    @Test
    void missingRefreshTokenIsTolerated() {
        Token token = client.parseToken(
                "{\"access_token\":\"abc\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        assertEquals("abc", token.accessToken());
        assertNull(token.refreshToken());
        assertEquals(3_600_000L, token.expiresIn());
    }

    @Test
    void missingAccessTokenFailsWithClearMessage() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> client.parseToken("{\"error\":\"invalid_client\"}"));
        assertTrue(e.getMessage().contains("OAuth2"));
        assertFalse(e.getCause() instanceof NullPointerException,
                "must be a validation failure, not an NPE");
    }
}
