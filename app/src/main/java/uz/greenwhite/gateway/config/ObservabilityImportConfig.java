package uz.greenwhite.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uz.greenwhite.qosid.observability.ObservabilityConfig;

/**
 * Pulls the shared Loki/Prometheus proxy beans into the gateway. settings-core is
 * never component-scanned, so the import is explicit — the same convention
 * {@link SettingsDbConfig} follows for the settings repositories.
 *
 * <p>The {@code SettingsResolver} these beans need is satisfied by
 * {@code SettingsService}, which implements it.
 */
@Configuration
@Import(ObservabilityConfig.class)
public class ObservabilityImportConfig {
}
