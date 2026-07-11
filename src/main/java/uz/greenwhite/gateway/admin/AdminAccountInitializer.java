package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.settings.SettingsService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Seeds the single admin account on first boot (admin/admin) and supports
 * password reset via env: ADMIN_RESET=true → reset to admin, or
 * ADMIN_PASSWORD_RESET=<value> → reset to that value.
 *
 * <p>Each reset directive is applied at most once: a signature of the directive
 * is stored under the {@code admin.reset-marker} settings key, so leaving the
 * env var set does not re-clobber a password the admin later changed. The marker
 * stores only a hash, never the plaintext password.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASS = "admin";
    private static final String RESET_MARKER_KEY = "admin.reset-marker";

    private final AdminAccountRepository repository;
    private final Environment environment;
    private final SettingsService settingsService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() == 0) {
            repository.insert(DEFAULT_USER, encoder.encode(DEFAULT_PASS));
            log.warn("Default admin account '{}' seeded with the default password — change it immediately after first login", DEFAULT_USER);
            return;
        }

        String directive = null;
        String explicitReset = environment.getProperty("ADMIN_PASSWORD_RESET");
        if (explicitReset != null && !explicitReset.isBlank()) {
            directive = "PW:" + sha256(explicitReset);
        } else if (Boolean.parseBoolean(environment.getProperty("ADMIN_RESET", "false"))) {
            directive = "DEFAULT";
        }
        if (directive == null) return;

        String marker = settingsService.get(RESET_MARKER_KEY, "");
        if (directive.equals(marker)) {
            log.info("Admin reset directive already applied (marker match) — skipping");
            return;
        }
        if (directive.startsWith("PW:")) {
            repository.updatePassword(DEFAULT_USER, encoder.encode(explicitReset));
            log.warn("Admin password reset via ADMIN_PASSWORD_RESET — remove the env var; further restarts with the same value are no-ops");
        } else {
            repository.updatePassword(DEFAULT_USER, encoder.encode(DEFAULT_PASS));
            log.warn("Admin password reset to default via ADMIN_RESET — change it and remove the env var; further restarts are no-ops");
        }
        settingsService.set(RESET_MARKER_KEY, directive, false, "admin-reset");
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
