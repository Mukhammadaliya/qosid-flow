package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.greenwhite.qosid.observability.SettingsResolver;
import uz.greenwhite.qosid.settings.SettingsRepository;

/**
 * Backs the shared Loki/Prometheus proxies with the shared settings DB.
 *
 * <p>The gateway answers the same seam from its in-memory cache; ops reads H2 per call
 * instead. That is deliberate: ops is a low-traffic console, and a cache here would go
 * stale the moment an operator edited the value through this very sidecar.
 *
 * <p>Only non-secret keys flow through here (two base URLs), so no cipher is involved —
 * a secret key would come back encrypted and is therefore never routed this way.
 */
@Component
@RequiredArgsConstructor
public class OpsSettingsResolver implements SettingsResolver {

    private final SettingsRepository repo;

    @Override
    public String get(String key, String fallback) {
        try {
            return repo.find(key)
                    .filter(r -> !r.isSecret())
                    .map(r -> r.getValue())
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(fallback);
        } catch (Exception e) {
            // A locked/absent settings DB must not take the diagnostics pages down with it.
            return fallback;
        }
    }
}
