package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the single admin account on first boot (admin/admin) and supports
 * password reset via env: ADMIN_RESET=true → reset to admin, or
 * ADMIN_PASSWORD_RESET=<value> → reset to that value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASS = "admin";

    private final AdminAccountRepository repository;
    private final Environment environment;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() == 0) {
            repository.insert(DEFAULT_USER, encoder.encode(DEFAULT_PASS));
            log.warn("Default admin account created: {}/{} — CHANGE THIS PASSWORD", DEFAULT_USER, DEFAULT_PASS);
            return;
        }
        String explicitReset = environment.getProperty("ADMIN_PASSWORD_RESET");
        if (explicitReset != null && !explicitReset.isBlank()) {
            repository.updatePassword(DEFAULT_USER, encoder.encode(explicitReset));
            log.warn("Admin password reset via ADMIN_PASSWORD_RESET env — remove the env var now");
            return;
        }
        if (Boolean.parseBoolean(environment.getProperty("ADMIN_RESET", "false"))) {
            repository.updatePassword(DEFAULT_USER, encoder.encode(DEFAULT_PASS));
            log.warn("Admin password reset to default via ADMIN_RESET=true — change it and remove the env var");
        }
    }
}
