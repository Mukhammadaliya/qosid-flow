package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsMigrationRunnerTest {

    @Mock SettingsRepository repo;
    @Mock SettingsService service;
    @Mock Environment env;

    @Test
    void migratesWhenMarkerAbsent() throws Exception {
        when(repo.find("gateway.settings.migration-completed")).thenReturn(Optional.empty()); // marker absent → migrate
        when(env.getProperty(anyString())).thenReturn(null);
        when(env.getProperty("gateway.retry.max-attempts")).thenReturn("3");

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service, atLeastOnce()).set(eq("gateway.retry.max-attempts"), eq("3"), eq(false), eq("migration"));
    }

    @Test
    void skipsWhenMarkerPresent() throws Exception {
        when(repo.find("gateway.settings.migration-completed")).thenReturn(Optional.of(
                SettingsRecord.builder().key("gateway.settings.migration-completed").value("true").build())); // marker present → skip

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service, never()).set(anyString(), anyString(), anyBoolean(), anyString());
    }
}
