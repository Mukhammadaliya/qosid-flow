package uz.greenwhite.qosid.observability;

/**
 * The one thing the shared Loki/Prometheus proxies need from a settings store:
 * resolve a key, fall back to the yaml/env value when there is no override.
 *
 * <p>Exists so {@code LogQueryService} / {@code StatsQueryService} can live in
 * settings-core and serve BOTH JVMs. The gateway backs it with its cached
 * {@code SettingsService}; the ops sidecar reads the shared H2 directly. Without
 * this seam the diagnostics modules would stay welded to the gateway — and go
 * dark exactly when the gateway is stopped and they are needed most.
 */
@FunctionalInterface
public interface SettingsResolver {

    /** @return the stored value for {@code key}, or {@code fallback} when unset/unreadable. */
    String get(String key, String fallback);
}
