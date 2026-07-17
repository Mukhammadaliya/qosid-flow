package uz.greenwhite.gateway.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthUtilTest {

    @Test
    void generatesRfc7617BasicAuthHeader() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("admin:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, AuthUtil.generateBasicAuth("admin", "secret"));
    }
}
