package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.source.SourceTokenService;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin view + diagnostics of the source-channel outbound auth (source pull/save, e.g. Biruni).
 * Stop-to-edit: config writes are refused on the running service (409 stop-required) —
 * editing happens via the ops sidecar while the service is stopped. Secrets are masked
 * on read. The test endpoint never calls the source pull endpoint — it consumes pending
 * production requests, so a live GET there would discard real work as a side effect.
 */
@Slf4j
@RestController
@RequestMapping("/qosid/admin/api/source-auth")
@RequiredArgsConstructor
public class SourceAuthController {

    private static final String MASK = "***";
    private final SettingsService settings;
    private final SourceAuthService sourceAuthService;
    private final SourceTokenService sourceTokenService;

    @GetMapping
    public ResponseEntity<Map<String, String>> get() {
        Map<String, String> out = new HashMap<>();
        out.put("type", settings.get("gateway.source.auth.type", "basic"));
        out.put("basicUsername", settings.get("gateway.source.auth.basic.username", ""));
        out.put("basicPassword", maskIfPresent("gateway.source.auth.basic.password"));
        out.put("apikeyHeader", settings.get("gateway.source.auth.apikey.header", "X-Api-Key"));
        out.put("apikeyValue", maskIfPresent("gateway.source.auth.apikey.value"));
        out.put("oauth2TokenUrl", settings.get("gateway.source.auth.oauth2.token-url", ""));
        out.put("oauth2ClientId", settings.get("gateway.source.auth.oauth2.client-id", ""));
        out.put("oauth2ClientSecret", maskIfPresent("gateway.source.auth.oauth2.client-secret"));
        out.put("oauth2Scope", settings.get("gateway.source.auth.oauth2.scope", ""));
        out.put("oauth2CacheTtlSeconds", settings.get("gateway.source.auth.oauth2.cache-ttl-seconds", ""));
        out.put("oauth2ClientType", settings.get("gateway.source.auth.oauth2.client-type", "standard"));
        return ResponseEntity.ok(out);
    }

    /**
     * Stop-to-edit: the running service refuses ALL settings writes. Source-auth is
     * edited only while the service is stopped, via the ops sidecar settings API.
     */
    @PutMapping
    public ResponseEntity<Map<String, String>> put() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "stop-required"));
    }

    /**
     * Validates and reports the configured source auth without ever calling the source
     * pull endpoint — that endpoint CONSUMES pending production requests (drained by
     * RequestPuller every 5s), so a live GET there would discard real work as a side effect.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> out = new HashMap<>();
        try {
            String type = settings.get("gateway.source.auth.type", "basic");
            if ("oauth2".equalsIgnoreCase(type)) {
                // The token mint is the meaningful test for oauth2 — no other config surface
                // to validate. Caching the resulting token is harmless (same cache the real
                // pull path uses).
                sourceTokenService.getBearerToken();
                out.put("ok", true);
                out.put("type", "oauth2");
                out.put("message", "Token obtained");
            } else {
                // basic/apikey: resolving authHeaders() proves the config builds without
                // error; no external call is made (pull endpoint consumes requests).
                sourceAuthService.authHeaders();
                out.put("ok", true);
                out.put("type", type);
                out.put("message", "Auth configured (no live connectivity test for basic/apikey — pull endpoint consumes requests)");
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    private String maskIfPresent(String key) {
        return settings.get(key, "").isBlank() ? "" : MASK;
    }
}
