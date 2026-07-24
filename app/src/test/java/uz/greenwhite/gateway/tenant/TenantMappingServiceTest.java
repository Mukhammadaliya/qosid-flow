package uz.greenwhite.gateway.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantMappingServiceTest {

    @Mock SettingsService settings;
    private final ObjectMapper om = new ObjectMapper();

    private TenantMappingService service() {
        return new TenantMappingService(settings, om);
    }

    @Test
    void toInternalReadsDefaultCompanyIdFilialId() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("filial_id");
        var node = om.readTree("{\"company_id\":100,\"filial_id\":5,\"request_id\":7,\"base_url\":\"http://x\"}");

        RequestMessage msg = service().toInternal(node);

        assertEquals(100L, msg.getTenantId());
        assertEquals(5L, msg.getExtraTenantId());
        assertEquals(7L, msg.getRequestId());
        assertEquals("http://x", msg.getBaseUrl());
    }

    @Test
    void toInternalReadsConfiguredFieldNames() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("org_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("branch_id");
        var node = om.readTree("{\"org_id\":42,\"branch_id\":9,\"request_id\":1}");

        RequestMessage msg = service().toInternal(node);

        assertEquals(42L, msg.getTenantId());
        assertEquals(9L, msg.getExtraTenantId());
    }

    @Test
    void toInternalMissingTenantYieldsNull() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("filial_id");
        var node = om.readTree("{\"request_id\":1}"); // no tenant field

        RequestMessage msg = service().toInternal(node);

        assertNull(msg.getTenantId());
        assertNull(msg.getExtraTenantId());
    }

    @Test
    void toInternalBlankExtraFieldSkipsExtra() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("");
        var node = om.readTree("{\"company_id\":100,\"filial_id\":5,\"request_id\":1}");

        RequestMessage msg = service().toInternal(node);

        assertEquals(100L, msg.getTenantId());
        assertNull(msg.getExtraTenantId()); // extra field disabled
    }

    @Test
    void toExternalEmitsConfiguredTenantKey() {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        Map<String, Object> out = service().toExternal(100L);
        assertEquals(Map.of("company_id", 100L), out);
    }

    @Test
    void toInternalNonNumericTenantYieldsNull() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("filial_id");
        var node = om.readTree("{\"company_id\":\"abc\",\"request_id\":1}");

        RequestMessage msg = service().toInternal(node);

        assertNull(msg.getTenantId());
    }

    @Test
    void toInternalStringNumberTenantIsParsed() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("filial_id");
        var node = om.readTree("{\"company_id\":\"42\",\"request_id\":1}");

        RequestMessage msg = service().toInternal(node);

        assertEquals(42L, msg.getTenantId());
    }

    /**
     * The source pull payload carries no created_at, so ingress must stamp it.
     * Without it {@code isMessageExpired} always returns false and the CB-blocked
     * re-queue path (SAFETY-RULES 23) loses its only backstop.
     */
    @Test
    void toInternalStampsCreatedAtWhenSourceOmitsIt() throws Exception {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        when(settings.get("gateway.tenant.extra-field-name", "filial_id")).thenReturn("filial_id");
        var node = om.readTree("{\"company_id\":100,\"request_id\":900123,\"base_url\":\"https://x\"}");

        RequestMessage msg = service().toInternal(node);

        assertNotNull(msg.getCreatedAt(), "ingress must stamp createdAt — the expiry net depends on it");
        assertTrue(msg.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(1)));
        assertEquals(100L, msg.getTenantId());
    }
}
