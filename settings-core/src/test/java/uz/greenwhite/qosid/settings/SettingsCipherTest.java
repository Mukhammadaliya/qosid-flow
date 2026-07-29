package uz.greenwhite.qosid.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsCipherTest {

    @Test
    void roundTripReturnsOriginal() {
        SettingsCipher cipher = new SettingsCipher("0123456789abcdef0123456789abcdef");
        String secret = "client-secret-xyz";
        String enc = cipher.encrypt(secret);
        assertNotEquals(secret, enc);
        assertEquals(secret, cipher.decrypt(enc));
        assertTrue(cipher.isEnabled());
    }

    @Test
    void sameInputProducesDifferentCiphertext() {
        SettingsCipher cipher = new SettingsCipher("0123456789abcdef0123456789abcdef");
        assertNotEquals(cipher.encrypt("x"), cipher.encrypt("x")); // random IV
    }

    @Test
    void blankKeyDisablesCipher() {
        SettingsCipher cipher = new SettingsCipher("");
        assertFalse(cipher.isEnabled());
    }
}
