package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.SourceAuthRequest;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;
import uz.greenwhite.gateway.source.SourceTokenService;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin management of the source-channel outbound auth (Biruni pull/save).
 * Secrets are stored AES-encrypted and masked on read. The test endpoint never calls the
 * Biruni pull endpoint — it consumes pending production requests, so a live GET there would
 * discard real work as a side effect.
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
        return ResponseEntity.ok(out);
    }

    @PutMapping
    public ResponseEntity<Void> put(@RequestBody SourceAuthRequest req) {
        String type = req.getType() == null ? "" : req.getType().toLowerCase();
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        switch (type) {
            case "basic" -> {
                requireNonBlank(req.getBasicUsername(), "basicUsername");
                boolean hasStoredPassword = !settings.get("gateway.source.auth.basic.password", "").isBlank();
                if (!hasStoredPassword) {
                    // I2: no password row exists yet — a username-only save would silently
                    // break live auth (empty-password Basic header). Require one up front.
                    requireNonBlank(req.getBasicPassword(), "basicPassword");
                }
                set("gateway.source.auth.basic.username", req.getBasicUsername(), false, admin);
                if (req.getBasicPassword() != null && !req.getBasicPassword().isBlank()) {
                    setSecret("gateway.source.auth.basic.password", req.getBasicPassword(), admin);
                }
            }
            case "apikey" -> {
                requireNonBlank(req.getApikeyHeader(), "apikeyHeader");
                requireNonBlank(req.getApikeyValue(), "apikeyValue");
                set("gateway.source.auth.apikey.header", req.getApikeyHeader(), false, admin);
                setSecret("gateway.source.auth.apikey.value", req.getApikeyValue(), admin);
            }
            case "oauth2" -> {
                requireNonBlank(req.getOauth2TokenUrl(), "oauth2TokenUrl");
                requireNonBlank(req.getOauth2ClientId(), "oauth2ClientId");
                requireNonBlank(req.getOauth2ClientSecret(), "oauth2ClientSecret");
                requireNumericIfPresent(req.getOauth2CacheTtlSeconds(), "oauth2CacheTtlSeconds");
                set("gateway.source.auth.oauth2.token-url", req.getOauth2TokenUrl(), false, admin);
                set("gateway.source.auth.oauth2.client-id", req.getOauth2ClientId(), false, admin);
                setSecret("gateway.source.auth.oauth2.client-secret", req.getOauth2ClientSecret(), admin);
                set("gateway.source.auth.oauth2.scope", nullToEmpty(req.getOauth2Scope()), false, admin);
                set("gateway.source.auth.oauth2.cache-ttl-seconds", nullToEmpty(req.getOauth2CacheTtlSeconds()), false, admin);
            }
            default -> throw new IllegalArgumentException("type must be basic|apikey|oauth2");
        }
        set("gateway.source.auth.type", type, false, admin);
        return ResponseEntity.noContent().build();
    }

    /**
     * Validates and reports the configured source auth without ever calling the Biruni
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

    private void set(String key, String value, boolean secret, String admin) {
        settings.set(key, value, secret, admin);
    }

    /**
     * Minor(a): {@link SettingsService#set} throws {@link IllegalStateException} when asked
     * to store a secret with encryption disabled. Left uncaught that maps to a 500; rethrow
     * as {@link IllegalArgumentException} so the global handler maps it to 400 with a clear cause.
     */
    private void setSecret(String key, String value, String admin) {
        try {
            settings.set(key, value, true, admin);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("encryption not configured — set SETTINGS_ENCRYPTION_KEY", e);
        }
    }

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    /** Minor(b): oauth2CacheTtlSeconds is optional, but if present it must parse as a long. */
    private static void requireNumericIfPresent(String v, String name) {
        if (v == null || v.isBlank()) return;
        try {
            Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be numeric");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
