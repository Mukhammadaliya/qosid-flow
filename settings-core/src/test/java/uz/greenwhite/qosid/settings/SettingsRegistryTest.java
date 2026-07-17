package uz.greenwhite.qosid.settings;

import org.junit.jupiter.api.Test;
import uz.greenwhite.qosid.settings.SettingsRegistry.EffectClass;

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
    void isEditableIsAnAllowlistOverRegistryKeys() {
        // Editable: registry keys with LIVE / REQUIRES_RESTART effect class
        assertTrue(SettingsRegistry.isEditable("gateway.api.base-url"));
        assertTrue(SettingsRegistry.isEditable("gateway.retry.max-attempts"));
        assertTrue(SettingsRegistry.isEditable("gateway.tenant.trust-extra-tenant-header"));
        // Not editable: READ_ONLY registry keys AND unknown keys
        assertFalse(SettingsRegistry.isEditable("server.port"));
        assertFalse(SettingsRegistry.isEditable("spring.kafka.bootstrap-servers"));
        assertFalse(SettingsRegistry.isEditable("gateway.kafka.bootstrap-servers")); // unknown
        assertFalse(SettingsRegistry.isEditable("gateway.polling.batch-size"));      // ghost key removed
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
