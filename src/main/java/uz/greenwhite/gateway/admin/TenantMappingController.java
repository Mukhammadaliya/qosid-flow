package uz.greenwhite.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.tenant.TenantMappingService;
import uz.greenwhite.gateway.tenant.TenantPair;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin diagnostics for the tenant mapping. Lets an operator paste a sample
 * Biruni pull element and see which tenant/extra the current configuration
 * would extract — without needing a live pull or the D-faza UI.
 */
@RestController
@RequestMapping("/qosid/admin/api/tenant-mapping")
@RequiredArgsConstructor
public class TenantMappingController {

    private final TenantMappingService tenantMappingService;

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody JsonNode sample) {
        TenantPair pair = tenantMappingService.extract(sample);
        Map<String, Object> out = new HashMap<>();
        out.put("tenantId", pair.tenantId());
        out.put("extraTenantId", pair.extraTenantId());
        out.put("tenantField", tenantMappingService.tenantField());
        out.put("extraField", tenantMappingService.extraField());
        return ResponseEntity.ok(out);
    }
}
