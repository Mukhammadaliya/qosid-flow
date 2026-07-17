package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.AiConfigRequest;
import uz.greenwhite.gateway.ai.AiAnalysisResult;
import uz.greenwhite.gateway.ai.AiAnalysisService;
import uz.greenwhite.gateway.ai.AiAnalyzeRequest;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in AI error analysis, under the /qosid/admin/api/** session-auth chain.
 * All responses are 200 (never 500) — analysis failures ride in the result's error field.
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

    @PutMapping("/config")
    public ResponseEntity<Void> putConfig(@RequestBody AiConfigRequest req) {
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        settings.set("gateway.ai.base-url", nullToEmpty(req.getBaseUrl()), false, admin);
        settings.set("gateway.ai.model", nullToEmpty(req.getModel()), false, admin);
        // Only persist the key when a new (non-mask) value is supplied.
        if (req.getApiKey() != null && !req.getApiKey().isBlank() && !MASK.equals(req.getApiKey())) {
            settings.set("gateway.ai.api-key", req.getApiKey(), true, admin);
        }
        return ResponseEntity.noContent().build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
