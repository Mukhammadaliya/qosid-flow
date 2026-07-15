package uz.greenwhite.gateway.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On every successful boot the DB settings have already been applied
 * (SettingsEnvironmentPostProcessor + fresh @ConfigurationProperties binding),
 * so any pending "restart required" flag is now satisfied. Clear it.
 * Note on ordering: @Order(100) makes this run BEFORE the unordered
 * SettingsMigrationRunner (unordered = LOWEST_PRECEDENCE). That is harmless:
 * first-boot migration only seeds yaml values into an empty DB and never sets
 * the restart-pending flag, so there is nothing for this runner to miss.
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class RestartPendingResetRunner implements ApplicationRunner {

    private final SettingsService settingsService;

    @Override
    public void run(ApplicationArguments args) {
        settingsService.clearRestartPending();
    }
}
