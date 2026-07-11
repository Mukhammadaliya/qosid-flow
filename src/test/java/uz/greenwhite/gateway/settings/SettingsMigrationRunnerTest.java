package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsMigrationRunnerTest {

    @Mock SettingsRepository repo;
    @Mock SettingsService service;
    @Mock Environment env;

    @Test
    void migratesWhenSettingsEmpty() throws Exception {
        when(repo.findAll()).thenReturn(List.of()); // empty → migrate
        when(env.getProperty(anyString())).thenReturn(null);
        when(env.getProperty("gateway.retry.max-attempts")).thenReturn("3");

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service, atLeastOnce()).set(eq("gateway.retry.max-attempts"), eq("3"), eq(false), eq("migration"));
    }

    @Test
    void skipsWhenSettingsPopulated() throws Exception {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("x").value("1").build())); // non-empty → skip

        SettingsMigrationRunner runner = new SettingsMigrationRunner(repo, service, env);
        runner.run(null);

        verify(service, never()).set(anyString(), anyString(), anyBoolean(), anyString());
    }
}
