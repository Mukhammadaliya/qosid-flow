package uz.greenwhite.gateway.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.gateway.tenant.TenantMappingService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BiruniClientEgressTest {

    @Mock SettingsService settings;

    @Test
    void toExternalDefaultKeyIsCompanyId() {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("company_id");
        TenantMappingService mapper = new TenantMappingService(settings, new ObjectMapper());
        Map<String, Object> body = mapper.toExternal(100L);
        assertTrue(body.containsKey("company_id"));
        assertEquals(100L, body.get("company_id"));
        assertFalse(body.containsKey("tenant_id")); // never leaks the internal name
    }

    @Test
    void toExternalHonorsConfiguredKey() {
        when(settings.get("gateway.tenant.field-name", "company_id")).thenReturn("org_id");
        TenantMappingService mapper = new TenantMappingService(settings, new ObjectMapper());
        assertEquals(Map.of("org_id", 7L), mapper.toExternal(7L));
    }
}
