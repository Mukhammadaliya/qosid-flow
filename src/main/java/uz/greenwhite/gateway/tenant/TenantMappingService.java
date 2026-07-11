package uz.greenwhite.gateway.tenant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.LinkedHashMap;
import java.util.List;
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
public class TenantMappingService {

    private static final String FIELD_KEY = "gateway.tenant.field-name";
    private static final String EXTRA_KEY = "gateway.tenant.extra-field-name";
    private static final String DEFAULT_FIELD = "company_id";
    private static final String DEFAULT_EXTRA = "filial_id";

    private final SettingsService settings;
    /**
     * Lenient copy used ONLY for the Biruni-pull ingress conversion: the configured tenant/extra
     * field names (e.g. company_id/filial_id) are unknown to RequestMessage and must be tolerated
     * here. This ingress copy-mapper tolerates unknown pull fields; the Kafka round-trip uses
     * spring-kafka's JsonDeserializer (also lenient by default — FAIL_ON_UNKNOWN_PROPERTIES is
     * off there too), and {@code @JsonAlias} on the tenant fields preserves back-compat with
     * pre-rename messages already sitting in topics at deploy time.
     */
    private final ObjectMapper lenientMapper;

    /**
     * Raw JSON keys that carry {@code @JsonAlias} on {@code RequestMessage.tenantId}/
     * {@code extraTenantId} (added for Kafka back-compat — see class javadoc). Left in place,
     * a Biruni pull element containing one of these would make the ingress
     * {@code convertValue} below bind it straight onto the strict {@code Long} tenantId/
     * extraTenantId — defeating the lenient, non-numeric-tolerant {@link #readLong} and
     * throwing on e.g. {@code {"company_id":"abc"}}. (A class-level
     * {@code @JsonIgnoreProperties} mixin does NOT help here: Jackson matches its ignore
     * list against a property's primary name, not its aliases.) So these two keys are
     * stripped from a throwaway copy of the node before conversion; {@link #toInternal}
     * always re-derives tenantId/extraTenantId from the untouched original via
     * {@link #extract} anyway, so nothing is lost.
     */
    private static final List<String> ALIASED_KEYS = List.of("company_id", "filial_id");

    public TenantMappingService(SettingsService settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.lenientMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** INGRESS: build a RequestMessage from a raw Biruni pull element. */
    public RequestMessage toInternal(JsonNode element) {
        JsonNode forConversion = element;
        if (element instanceof ObjectNode on && (on.has("company_id") || on.has("filial_id"))) {
            forConversion = on.deepCopy().remove(ALIASED_KEYS);
        }
        RequestMessage msg = lenientMapper.convertValue(forConversion, RequestMessage.class);
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
        if (n == null || n.isNull()) return null;
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isTextual()) {
            try {
                return Long.parseLong(n.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null; // object/array/boolean/float → not a valid tenant id
    }
}
