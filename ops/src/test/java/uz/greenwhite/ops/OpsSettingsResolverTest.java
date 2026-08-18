package uz.greenwhite.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings seam the shared Loki/Prometheus proxies read through on the ops side.
 *
 * <p>What matters here is which value wins: an operator who moved Loki via the settings DB
 * must see the ops-side Errors module follow, and a missing row must leave the yaml/env
 * value in charge rather than resolving to null and blanking the base URL.
 */
@SpringBootTest(properties = {
        "ops.settings-db-path=mem:opsresolver;DB_CLOSE_DELAY=-1",
        "ops.settings-encryption-key=test-aes-key",
        "ops.gateway.start-command=echo s",
        "ops.gateway.stop-command=echo s"
})
class OpsSettingsResolverTest {

    @Autowired OpsSettingsResolver resolver;
    @Autowired JdbcTemplate opsJdbc;
    @MockitoBean GatewayStatusService status;

    @BeforeEach
    void clean() {
        opsJdbc.update("DELETE FROM settings");
    }

    @Test
    void storedValueWinsOverTheFallback() {
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway.loki.url", "http://loki-moved:3100");

        assertThat(resolver.get("gateway.loki.url", "http://localhost:3100"))
                .isEqualTo("http://loki-moved:3100");
    }

    @Test
    void missingRowFallsBackToTheYamlValue() {
        assertThat(resolver.get("gateway.loki.url", "http://localhost:3100"))
                .isEqualTo("http://localhost:3100");
    }

    @Test
    void blankStoredValueFallsBackRatherThanBlankingTheUrl() {
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, FALSE)",
                "gateway.loki.url", "   ");

        assertThat(resolver.get("gateway.loki.url", "http://localhost:3100"))
                .isEqualTo("http://localhost:3100");
    }

    @Test
    void secretRowIsNeverReturnedAsPlaintext() {
        // Secrets sit encrypted in the column. Returning one here would hand a caller
        // ciphertext dressed up as a URL, so the fallback is the only correct answer.
        opsJdbc.update("INSERT INTO settings (skey, svalue, secret) VALUES (?, ?, TRUE)",
                "gateway.loki.url", "AES:whatever");

        assertThat(resolver.get("gateway.loki.url", "http://localhost:3100"))
                .isEqualTo("http://localhost:3100");
    }
}
