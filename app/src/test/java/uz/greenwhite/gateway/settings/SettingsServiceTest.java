package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.qosid.settings.SettingsCipher;
import uz.greenwhite.qosid.settings.SettingsRecord;
import uz.greenwhite.qosid.settings.SettingsRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock SettingsRepository repo;
    @Mock SettingsCipher cipher;

    private SettingsService service() {
        SettingsService s = new SettingsService(repo, cipher);
        s.reload();
        return s;
    }

    @Test
    void getReturnsCachedValueElseDefault() {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("a").value("1").secret(false).build()));
        SettingsService s = service();
        assertEquals("1", s.get("a", "def"));
        assertEquals("def", s.get("missing", "def"));
    }

    @Test
    void getSecretDecrypts() {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("pw").value("ENC").secret(true).build()));
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.decrypt("ENC")).thenReturn("plain");
        SettingsService s = service();
        assertEquals("plain", s.getSecret("pw", "def"));
    }

    @Test
    void setEncryptsSecretAndAudits() {
        when(repo.findAll()).thenReturn(List.of());
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.encrypt("plain")).thenReturn("ENC");
        SettingsService s = service();

        s.set("pw", "plain", true, "admin");

        verify(cipher).encrypt("plain");
        verify(repo).upsert("pw", "ENC", true, "admin");
        verify(repo).insertAudit(eq("pw"), any(), eq("***"), eq("admin")); // secret masked in audit
        // Cache holds ciphertext "ENC"; getSecret decrypts it back to "plain"
        when(cipher.decrypt("ENC")).thenReturn("plain");
        assertEquals("plain", s.getSecret("pw", "x"));
    }

    @Test
    void getAllMasksSecrets() {
        when(repo.findAll()).thenReturn(List.of(
                SettingsRecord.builder().key("pw").value("ENC").secret(true).build(),
                SettingsRecord.builder().key("a").value("1").secret(false).build()));
        SettingsService s = service();
        List<SettingsRecord> all = s.getAllMasked();
        assertEquals("***", all.stream().filter(r -> r.getKey().equals("pw")).findFirst().get().getValue());
        assertEquals("1", all.stream().filter(r -> r.getKey().equals("a")).findFirst().get().getValue());
    }
}
