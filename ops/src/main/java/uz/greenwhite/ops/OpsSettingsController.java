package uz.greenwhite.ops;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.qosid.settings.SettingsRepository;

import java.util.List;
import java.util.Map;

/**
 * Stop-to-edit settings API (session + CSRF protected by OpsSecurityConfig — /ops/**).
 * Thin: all decisions live in {@link OpsSettingsService}; audit is a direct
 * passthrough of the repository's lowercase-aliased rows (skey/old_value/new_value/
 * changed_by/changed_at — the same JSON contract the SPA already consumes).
 */
@RestController
@RequestMapping("/ops/api/settings")
@RequiredArgsConstructor
public class OpsSettingsController {

    private final OpsSettingsService settingsService;
    private final SettingsRepository settingsRepository;

    public record PutRequest(String value) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return settingsService.list();
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, String>> put(@PathVariable String key, @RequestBody PutRequest body) {
        return settingsService.put(key, body.value() == null ? "" : body.value());
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return settingsRepository.findAudit(200);
    }
}
