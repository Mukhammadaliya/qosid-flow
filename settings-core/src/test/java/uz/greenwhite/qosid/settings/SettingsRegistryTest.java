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
    void oauth2ProviderUriIsLiveEditable() {
        // Feature B: the Biruni OAuth2 provider-data endpoint is request-lifecycle → UI-editable (LIVE).
        assertEquals(EffectClass.LIVE, SettingsRegistry.effectClassOf("gateway.source.oauth2-provider-uri"));
        assertTrue(SettingsRegistry.isKnown("gateway.source.oauth2-provider-uri"));
        assertTrue(SettingsRegistry.isEditable("gateway.source.oauth2-provider-uri"));
        assertFalse(SettingsRegistry.isReadOnly("gateway.source.oauth2-provider-uri"));
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
    void isKnownIsTrueOnlyForRegistryKeys() {
        assertTrue(SettingsRegistry.isKnown("gateway.api.base-url"));         // LIVE
        assertTrue(SettingsRegistry.isKnown("server.port"));                  // READ_ONLY still known
        assertFalse(SettingsRegistry.isKnown("gateway.kafka.bootstrap-servers")); // unknown
        assertFalse(SettingsRegistry.isKnown("gateway.source.auth.basic.password")); // secret, dedicated page
    }

    @Test
    void isDbManagedTruthTable() {
        // DB-only, UI-managed keys (source-auth + AI) — not in the registry, but first-time
        // creatable via the ops stop-to-edit API
        assertTrue(SettingsRegistry.isDbManaged("gateway.source.auth.type"));
        assertTrue(SettingsRegistry.isDbManaged("gateway.source.auth.basic.password"));
        assertTrue(SettingsRegistry.isDbManaged("gateway.source.auth.oauth2.client-secret"));
        assertTrue(SettingsRegistry.isDbManaged("gateway.source.auth.oauth2.client-type"));
        assertTrue(SettingsRegistry.isDbManaged("gateway.ai.api-key"));
        assertTrue(SettingsRegistry.isDbManaged("gateway.ai.base-url"));
        // Registry keys and unknown keys are NOT db-managed
        assertFalse(SettingsRegistry.isDbManaged("gateway.api.base-url"));
        assertFalse(SettingsRegistry.isDbManaged("foo.bar"));
    }

    @Test
    void isDbManagedSecretTruthTable() {
        // Secret db-managed keys (AES at rest)
        assertTrue(SettingsRegistry.isDbManagedSecret("gateway.source.auth.basic.password"));
        assertTrue(SettingsRegistry.isDbManagedSecret("gateway.source.auth.apikey.value"));
        assertTrue(SettingsRegistry.isDbManagedSecret("gateway.source.auth.oauth2.client-secret"));
        assertTrue(SettingsRegistry.isDbManagedSecret("gateway.ai.api-key"));
        // Non-secret db-managed keys
        assertFalse(SettingsRegistry.isDbManagedSecret("gateway.source.auth.type"));
        assertFalse(SettingsRegistry.isDbManagedSecret("gateway.source.auth.oauth2.token-url"));
        assertFalse(SettingsRegistry.isDbManagedSecret("gateway.source.auth.oauth2.client-type"));
        assertFalse(SettingsRegistry.isDbManagedSecret("gateway.ai.model"));
        // Unknown key -> false
        assertFalse(SettingsRegistry.isDbManagedSecret("foo.bar"));
    }

    @Test
    void dbManagedKeysStayOutOfRegistryMetadata() {
        // They must NOT appear in GET /settings/registry (SettingsRegistry.all()) nor be
        // injected by the boot PostProcessor (isEditable allowlist)
        assertFalse(SettingsRegistry.isKnown("gateway.source.auth.type"));
        assertFalse(SettingsRegistry.isKnown("gateway.ai.api-key"));
        assertFalse(SettingsRegistry.isEditable("gateway.source.auth.type"));
        assertFalse(SettingsRegistry.isEditable("gateway.ai.api-key"));
        assertTrue(SettingsRegistry.all().stream()
                .noneMatch(m -> SettingsRegistry.isDbManaged(m.key())));
    }

    @Test
    void isInternalMatchesBookkeepingMarkers() {
        assertTrue(SettingsRegistry.isInternal("admin.reset-marker"));
        assertTrue(SettingsRegistry.isInternal("gateway._meta.restart-pending"));
        assertTrue(SettingsRegistry.isInternal("gateway.settings.migration-completed"));
        // Real, user-facing keys are NOT internal
        assertFalse(SettingsRegistry.isInternal("gateway.api.base-url"));
        assertFalse(SettingsRegistry.isInternal("gateway.source.auth.type"));
        assertFalse(SettingsRegistry.isInternal(null));
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
