package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import uz.greenwhite.gateway.admin.dto.SourceAuthRequest;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.source.SourceAuthService;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin management of the source-channel outbound auth (Biruni pull/save).
 * Secrets are stored AES-encrypted and masked on read. The test endpoint issues
 * one light authenticated request to the source pull URI to validate the config.
 */
@Slf4j
@RestController
@RequestMapping("/qosid/admin/api/source-auth")
@RequiredArgsConstructor
public class SourceAuthController {

    private static final String MASK = "***";
    private final SettingsService settings;
    private final SourceAuthService sourceAuthService;
    private final GatewayProperties gatewayProperties;

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
                set("gateway.source.auth.basic.username", req.getBasicUsername(), false, admin);
                if (req.getBasicPassword() != null && !req.getBasicPassword().isBlank()) {
                    set("gateway.source.auth.basic.password", req.getBasicPassword(), true, admin);
                }
            }
            case "apikey" -> {
                requireNonBlank(req.getApikeyHeader(), "apikeyHeader");
                requireNonBlank(req.getApikeyValue(), "apikeyValue");
                set("gateway.source.auth.apikey.header", req.getApikeyHeader(), false, admin);
                set("gateway.source.auth.apikey.value", req.getApikeyValue(), true, admin);
            }
            case "oauth2" -> {
                requireNonBlank(req.getOauth2TokenUrl(), "oauth2TokenUrl");
                requireNonBlank(req.getOauth2ClientId(), "oauth2ClientId");
                requireNonBlank(req.getOauth2ClientSecret(), "oauth2ClientSecret");
                set("gateway.source.auth.oauth2.token-url", req.getOauth2TokenUrl(), false, admin);
                set("gateway.source.auth.oauth2.client-id", req.getOauth2ClientId(), false, admin);
                set("gateway.source.auth.oauth2.client-secret", req.getOauth2ClientSecret(), true, admin);
                set("gateway.source.auth.oauth2.scope", nullToEmpty(req.getOauth2Scope()), false, admin);
                set("gateway.source.auth.oauth2.cache-ttl-seconds", nullToEmpty(req.getOauth2CacheTtlSeconds()), false, admin);
            }
            default -> throw new IllegalArgumentException("type must be basic|apikey|oauth2");
        }
        set("gateway.source.auth.type", type, false, admin);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> out = new HashMap<>();
        try {
            Map<String, String> authHeaders = sourceAuthService.authHeaders();
            int status = RestClient.create().get()
                    .uri(gatewayProperties.getBaseUrl() + gatewayProperties.getRequestPullUri())
                    .headers(h -> authHeaders.forEach(h::add))
                    .retrieve()
                    .onStatus(s -> true, (rq, rs) -> {}) // don't throw on non-2xx
                    .toBodilessEntity()
                    .getStatusCode().value();
            out.put("ok", status >= 200 && status < 400);
            out.put("httpStatus", status);
            out.put("message", "Reached source with configured auth");
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

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
