package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.ai.AiAnalysisResult;
import uz.greenwhite.gateway.ai.AiAnalysisService;
import uz.greenwhite.gateway.ai.AiAnalyzeRequest;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in AI error analysis, under the /qosid/admin/api/** session-auth chain.
 * Status/analyze/config-read are 200 (never 500) — analysis failures ride in the
 * result's error field. Stop-to-edit: the config write is refused on the running
 * service (409 stop-required) — editing happens via the ops sidecar while stopped.
 */
@RestController
@RequestMapping("/qosid/admin/api/ai")
@RequiredArgsConstructor
public class AiController {

    private static final String MASK = "***";
    private final AiAnalysisService aiAnalysisService;
    private final SettingsService settings;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("enabled", aiAnalysisService.enabled()));
    }

    @PostMapping("/analyze")
    public ResponseEntity<AiAnalysisResult> analyze(@RequestBody AiAnalyzeRequest req) {
        return ResponseEntity.ok(aiAnalysisService.analyze(req));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        Map<String, String> out = new HashMap<>();
        out.put("baseUrl", settings.get("gateway.ai.base-url", ""));
        out.put("model", settings.get("gateway.ai.model", ""));
        out.put("apiKey", settings.get("gateway.ai.api-key", "").isBlank() ? "" : MASK);
        return ResponseEntity.ok(out);
    }

    /**
     * Stop-to-edit: the running service refuses ALL settings writes. AI config is
     * edited only while the service is stopped, via the ops sidecar settings API.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, String>> putConfig() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "stop-required"));
    }
}
