package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code dev} profile used to expose the actuator {@code shutdown} endpoint (an
 * unauthenticated remote kill switch on the management port) purely so a local Windows
 * helper script could stop the JVM gracefully. That script is not in the repo and prod
 * stops the gateway with {@code systemctl stop} / SIGTERM, so nothing needs it.
 *
 * <p>This test reads the shipped {@code application.yml} directly rather than booting a
 * context: an accidental re-add in ANY profile document must fail the build, and a
 * profile-scoped document is invisible to a context started without that profile.</p>
 */
class ActuatorShutdownDisabledTest {

    private static String yaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml",
                new ClassPathResource("application.yml"));
        assertFalse(sources.isEmpty(), "application.yml must be on the test classpath");
        StringBuilder all = new StringBuilder();
        for (PropertySource<?> source : sources) {
            org.springframework.core.env.EnumerablePropertySource<?> enumerable =
                    (org.springframework.core.env.EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                all.append(name).append('=').append(enumerable.getProperty(name)).append('\n');
            }
        }
        return all.toString();
    }

    @Test
    void shutdownEndpointIsNeverExposedInAnyProfile() throws Exception {
        String all = yaml();
        assertNotNull(all);
        assertFalse(all.contains("management.endpoint.shutdown.enabled=true"),
                "The actuator shutdown endpoint must stay disabled — it is an unauthenticated kill switch");
        for (String line : all.split("\n")) {
            if (line.startsWith("management.endpoints.web.exposure.include=")) {
                assertFalse(line.contains("shutdown"),
                        "shutdown must not be in the exposed endpoint set: " + line);
                assertTrue(line.contains("health") && line.contains("prometheus"),
                        "health + prometheus must stay exposed (Loki/Prometheus/ops health poll): " + line);
            }
        }
    }
}
