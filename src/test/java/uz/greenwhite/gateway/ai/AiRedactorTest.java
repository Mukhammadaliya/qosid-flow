package uz.greenwhite.gateway.ai;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AiRedactorTest {

    @Test
    void masksBearerToken() {
        assertThat(AiRedactor.redact("GET failed, Authorization: Bearer abc.DEF-123 rejected"))
                .contains("Bearer ***").doesNotContain("abc.DEF-123");
    }

    @Test
    void masksKeyValueSecrets() {
        assertThat(AiRedactor.redact("password=hunter2 and token: xyz789 and api_key=k-42"))
                .contains("password=***").contains("token:***").contains("api_key=***")
                .doesNotContain("hunter2").doesNotContain("xyz789").doesNotContain("k-42");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        assertThat(AiRedactor.redact("connection timeout to supplier37 after 30s"))
                .isEqualTo("connection timeout to supplier37 after 30s");
    }

    @Test
    void nullSafe() {
        assertThat(AiRedactor.redact(null)).isEqualTo("");
    }
}
