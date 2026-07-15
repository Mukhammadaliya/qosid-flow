package uz.greenwhite.gateway.settings;

import org.junit.jupiter.api.Test;
import uz.greenwhite.gateway.settings.SettingsRegistry.EffectClass;

import static org.junit.jupiter.api.Assertions.*;

class SettingsRegistryTest {

    @Test
    void classifiesKnownKeys() {
        assertEquals(EffectClass.LIVE, SettingsRegistry.effectClassOf("gateway.api.base-url"));
        assertEquals(EffectClass.LIVE, SettingsRegistry.effectClassOf("gateway.source.base-url"));
        assertEquals(EffectClass.REQUIRES_RESTART, SettingsRegistry.effectClassOf("gateway.retry.max-attempts"));
        assertEquals(EffectClass.REQUIRES_RESTART, SettingsRegistry.effectClassOf("gateway.polling.enabled"));
        assertEquals(EffectClass.READ_ONLY, SettingsRegistry.effectClassOf("server.port"));
    }

    @Test
    void unknownGatewayKeyDefaultsToRequiresRestart() {
        assertEquals(EffectClass.REQUIRES_RESTART, SettingsRegistry.effectClassOf("gateway.some.future.key"));
    }

    @Test
    void isReadOnlyMatchesRegistry() {
        assertTrue(SettingsRegistry.isReadOnly("server.port"));
        assertFalse(SettingsRegistry.isReadOnly("gateway.api.base-url"));
    }

    @Test
    void allEntriesHaveGroupAndLabel() {
        assertFalse(SettingsRegistry.all().isEmpty());
        SettingsRegistry.all().forEach(m -> {
            assertNotNull(m.group());
            assertFalse(m.group().isBlank());
            assertNotNull(m.label());
            assertFalse(m.label().isBlank());
            assertNotNull(m.effectClass());
        });
    }
}
