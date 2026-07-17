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
        when(repo.find("gateway.source.auth.basic.username")).thenReturn(Optional.empty());
        when(repo.find("gateway.source.auth.basic.password")).thenReturn(Optional.empty());
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
        when(repo.find("gateway.source.auth.basic.username")).thenReturn(Optional.of(
                SettingsRecord.builder().key("gateway.source.auth.basic.username").value("existing").build())); // already migrated → no-op
        when(repo.find("gateway.source.auth.basic.password")).thenReturn(Optional.of(
                SettingsRecord.builder().key("gateway.source.auth.basic.password").value("enc").secret(true).build()));

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service, never()).set(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void upgradedDeploymentStillMigratesSourceBasicCreds() throws Exception {
        // I1: marker already present (upgraded prod, migrated before B3 existed) but the
        // source basic username row is absent — must still migrate it, unconditionally.
        when(repo.find("gateway.settings.migration-completed")).thenReturn(Optional.of(
                SettingsRecord.builder().key("gateway.settings.migration-completed").value("true").build()));
        when(repo.find("gateway.source.auth.basic.username")).thenReturn(Optional.empty());
        when(repo.find("gateway.source.auth.basic.password")).thenReturn(Optional.empty());
        when(env.getProperty("gateway.source.username")).thenReturn("legacyUser");
        when(env.getProperty("gateway.source.password")).thenReturn(null);

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service).set(eq("gateway.source.auth.basic.username"), eq("legacyUser"), eq(false), eq("migration"));
    }
}
