package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.qosid.observability.ObservabilityConfig;
import uz.greenwhite.qosid.observability.logs.LogQueryResult;
import uz.greenwhite.qosid.observability.logs.LogQueryService;
import uz.greenwhite.qosid.observability.stats.StatsQueryService;
import uz.greenwhite.qosid.observability.stats.StatsResult;

import java.util.Map;

/**
 * Ops-side twin of the gateway's {@code /qosid/admin/api/logs} and
 * {@code /qosid/admin/api/stats}: read-only proxies over Loki and Prometheus.
 *
 * <p>Why it exists: Loki and Prometheus keep running when the gateway JVM is stopped, but
 * the only route to them used to run through the gateway — so the console's Errors and
 * Statistics modules went dark in the stop-to-edit state, i.e. whenever an operator had
 * most reason to look at them. Same query surface, same never-500 degrade contract; served
 * by the sidecar so the diagnosis outlives the service being diagnosed.
 *
 * <p>Sits on the {@code /ops/**} chain, so {@code OPS_SESSION} auth applies exactly as it
 * does to start/stop and settings. Read-only, hence no CSRF concern (GET only).
 */
@RestController
@RequestMapping("/ops/api")
@RequiredArgsConstructor
public class OpsObservabilityController {

    private final LogQueryService logQueryService;
    private final StatsQueryService statsQueryService;

    @GetMapping("/logs")
    public ResponseEntity<LogQueryResult> logs(
            @RequestParam String source,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String container,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(logQueryService.query(source, from, to, q, container, level, limit));
    }

    @GetMapping("/logs/labels")
    public ResponseEntity<Map<String, Object>> labels(@RequestParam String name) {
        return ResponseEntity.ok(Map.of("values", logQueryService.labelValues(name)));
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsResult> stats(
            @RequestParam String preset,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Integer step) {
        return ResponseEntity.ok(statsQueryService.query(preset, from, to, step));
    }
}

/**
 * settings-core is wired explicitly, never component-scanned — the same convention
 * {@link OpsDbConfig} follows. {@link OpsSettingsResolver} supplies the settings seam
 * the imported beans depend on.
 */
@Configuration
@Import(ObservabilityConfig.class)
class OpsObservabilityImportConfig {
}
