package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.qosid.settings.SettingsCipher;
import uz.greenwhite.qosid.settings.SettingsRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceRestartPendingTest {

    @Mock SettingsRepository repo;
    @Mock SettingsCipher cipher;

    private SettingsService service() {
        SettingsService s = new SettingsService(repo, cipher);
        s.reload();
        return s;
    }

    @Test
    void markAndReadRestartPending() {
        when(repo.findAll()).thenReturn(List.of());
        SettingsService s = service();
        assertFalse(s.isRestartPending());

        s.markRestartPending();
        verify(repo).upsert("gateway._meta.restart-pending", "true", false, "system");
        assertTrue(s.isRestartPending());

        s.clearRestartPending();
        verify(repo).upsert("gateway._meta.restart-pending", "false", false, "system");
        assertFalse(s.isRestartPending());
    }

    @Test
    void markRestartPendingWritesNoAudit() {
        when(repo.findAll()).thenReturn(List.of());
        SettingsService s = service();
        s.markRestartPending();
        verify(repo, never()).insertAudit(any(), any(), any(), any());
    }
}
