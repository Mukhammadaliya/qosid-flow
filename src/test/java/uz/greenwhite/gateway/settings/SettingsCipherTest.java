package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.config.SettingsProperties;

import static org.junit.jupiter.api.Assertions.*;

class SettingsCipherTest {

    private SettingsCipher withKey(String key) {
        SettingsProperties props = new SettingsProperties();
        props.setEncryptionKey(key);
        return new SettingsCipher(props);
    }

    @Test
    void roundTripReturnsOriginal() {
        SettingsCipher cipher = withKey("0123456789abcdef0123456789abcdef");
        String secret = "client-secret-xyz";
        String enc = cipher.encrypt(secret);
        assertNotEquals(secret, enc);
        assertEquals(secret, cipher.decrypt(enc));
        assertTrue(cipher.isEnabled());
    }

    @Test
    void sameInputProducesDifferentCiphertext() {
        SettingsCipher cipher = withKey("0123456789abcdef0123456789abcdef");
        assertNotEquals(cipher.encrypt("x"), cipher.encrypt("x")); // random IV
    }

    @Test
    void blankKeyDisablesCipher() {
        SettingsCipher cipher = withKey("");
        assertFalse(cipher.isEnabled());
    }
}
