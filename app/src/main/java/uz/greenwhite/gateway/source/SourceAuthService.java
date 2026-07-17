package uz.greenwhite.gateway.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.GatewayProperties;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.util.AuthUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the outbound auth header(s) for the SOURCE channel (Biruni pull/save).
 * The type ({@code basic}|{@code apikey}|{@code oauth2}) is read per call from
 * settings so a UI change takes effect immediately. Basic falls back to the yaml
 * GatewayProperties when the settings DB has no credentials (dev / pre-migration).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAuthService {

    private final SettingsService settings;
    private final SourceTokenService sourceTokenService;
    private final GatewayProperties gatewayProperties;

    public Map<String, String> authHeaders() {
        String type = settings.get("gateway.source.auth.type", "basic");
        Map<String, String> headers = new LinkedHashMap<>();
        switch (type == null ? "basic" : type.toLowerCase()) {
            case "apikey" -> {
                String header = settings.get("gateway.source.auth.apikey.header", "X-Api-Key");
                String value = settings.getSecret("gateway.source.auth.apikey.value", "");
                headers.put(header, value);
            }
            case "oauth2" -> headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + sourceTokenService.getBearerToken());
            default -> headers.put(HttpHeaders.AUTHORIZATION, basicHeader());
        }
        return headers;
    }

    private String basicHeader() {
        String user = settings.get("gateway.source.auth.basic.username", "");
        String pass = settings.getSecret("gateway.source.auth.basic.password", "");
        if (user == null || user.isBlank() || pass == null || pass.isBlank()) {
            // Fallback to yaml GatewayProperties when EITHER credential is blank (dev /
            // pre-migration / blank-encryption-key deploy where the username migrated but
            // the secret password did not — never send Basic with an empty password).
            user = gatewayProperties.getUsername();
            pass = gatewayProperties.getPassword();
        }
        return AuthUtil.generateBasicAuth(user, pass);
    }
}
