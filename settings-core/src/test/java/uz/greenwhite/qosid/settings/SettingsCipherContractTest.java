package uz.greenwhite.qosid.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SettingsCipherContractTest {

    private static final String KEY = "contract-test-key-2026";

    @Test
    void roundTripAcrossInstances() {
        String ct = new SettingsCipher(KEY).encrypt("maxfiy-qiymat-™");
        assertEquals("maxfiy-qiymat-™", new SettingsCipher(KEY).decrypt(ct));
    }

    /** Format-freeze: shu fixture task davomida bir marta generatsiya qilingan va literal saqlangan.
     *  Kelajakda cipher o'zgartirilsa bu test eski qiymatlar o'qilishini kafolatlaydi. */
    @Test
    void decryptsFrozenFixture() {
        String frozen = "08s7bFc1cBtXVf4Kr8xdn3Dilwpv9CphWqWFzlcRzl8MDoGq6f1LVSLPWlo=";
        assertEquals("frozen-plaintext", new SettingsCipher(KEY).decrypt(frozen));
    }

    @Test
    void disabledWhenKeyBlank() {
        assertFalse(new SettingsCipher("").isEnabled());
    }
}
