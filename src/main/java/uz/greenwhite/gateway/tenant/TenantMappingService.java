package uz.greenwhite.gateway.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anti-corruption boundary between the main app's field names and the internal
 * tenant model. Ingress ({@link #toInternal}) reads the UI-configured field
 * names (default company_id/filial_id) from a Biruni pull JSON element into
 * tenantId/extraTenantId. Egress ({@link #toExternal}) reverses tenantId back to
 * the configured key so bmb receives the shape it expects. Field names are read
 * per call from SettingsService (dynamic — a UI change takes effect immediately).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMappingService {

    private static final String FIELD_KEY = "gateway.tenant.field-name";
    private static final String EXTRA_KEY = "gateway.tenant.extra-field-name";
    private static final String DEFAULT_FIELD = "company_id";
    private static final String DEFAULT_EXTRA = "filial_id";

    private final SettingsService settings;
    private final ObjectMapper objectMapper;

    /** INGRESS: build a RequestMessage from a raw Biruni pull element. */
    public RequestMessage toInternal(JsonNode element) {
        RequestMessage msg = objectMapper.convertValue(element, RequestMessage.class);
        TenantPair pair = extract(element);
        msg.setTenantId(pair.tenantId());
        msg.setExtraTenantId(pair.extraTenantId());
        return msg;
    }

    /** Extract tenant/extra from a payload using the configured field names. */
    public TenantPair extract(JsonNode element) {
        String field = settings.get(FIELD_KEY, DEFAULT_FIELD);
        String extraField = settings.get(EXTRA_KEY, DEFAULT_EXTRA);
        Long tenant = readLong(element, field);
        Long extra = (extraField != null && !extraField.isBlank()) ? readLong(element, extraField) : null;
        return new TenantPair(tenant, extra);
    }

    /** EGRESS: configured tenant key → value, for the Biruni save / OAuth fetch body. */
    public Map<String, Object> toExternal(Long tenantId) {
        String field = settings.get(FIELD_KEY, DEFAULT_FIELD);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(field, tenantId);
        return out;
    }

    /** The currently-configured tenant field name (for diagnostics / the test endpoint). */
    public String tenantField() {
        return settings.get(FIELD_KEY, DEFAULT_FIELD);
    }

    /** The currently-configured extra-tenant field name (may be blank). */
    public String extraField() {
        return settings.get(EXTRA_KEY, DEFAULT_EXTRA);
    }

    private static Long readLong(JsonNode element, String field) {
        if (field == null || field.isBlank()) return null;
        JsonNode n = element.get(field);
        return (n != null && !n.isNull()) ? n.asLong() : null;
    }
}
