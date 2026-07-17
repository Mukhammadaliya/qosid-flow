package uz.greenwhite.gateway.admin;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.SettingUpdateRequest;
import uz.greenwhite.gateway.admin.dto.SettingsRegistryResponse;
import uz.greenwhite.gateway.settings.SettingsService;
import uz.greenwhite.qosid.settings.SettingsRecord;
import uz.greenwhite.qosid.settings.SettingsRegistry;
import uz.greenwhite.qosid.settings.SettingsRegistry.EffectClass;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/qosid/admin/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public SettingsController(SettingsService settingsService,
                              @Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc,
                              Environment environment) {
        this.settingsService = settingsService;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    /** Registry-driven view: each known setting with metadata, current value, effect-class. */
    @GetMapping("/registry")
    public ResponseEntity<SettingsRegistryResponse> registry() {
        List<SettingsRegistryResponse.Item> items = SettingsRegistry.all().stream()
                .map(m -> {
                    String current = settingsService.get(m.key(), environment.getProperty(m.key()));
                    String display = m.secret() ? "***" : current;
                    return new SettingsRegistryResponse.Item(
                            m.key(), m.group(), m.label(), m.type(),
                            m.effectClass().name(), display, m.secret());
                })
                .toList();
        return ResponseEntity.ok(new SettingsRegistryResponse(items, settingsService.isRestartPending()));
    }

    @GetMapping
    public ResponseEntity<List<SettingsRecord>> getAll() {
        return ResponseEntity.ok(settingsService.getAllMasked());
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> update(@PathVariable String key, @RequestBody SettingUpdateRequest req) {
        // Allowlist: only editable registry keys (LIVE/REQUIRES_RESTART) may be written.
        // Rejects both READ_ONLY infra keys AND unknown keys (e.g. a hand-crafted PUT of
        // spring.kafka.bootstrap-servers), which would otherwise be injected into the
        // Environment on next boot. Source-auth/AI keys have their own endpoints.
        if (!SettingsRegistry.isEditable(key)) {
            throw new IllegalArgumentException("Setting '" + key + "' is not editable");
        }
        if ("gateway.tenant.field-name".equals(key)
                && (req.getValue() == null || req.getValue().isBlank())) {
            throw new IllegalArgumentException("gateway.tenant.field-name must not be blank");
        }
        if ("gateway.tenant.default-rest-tenant".equals(key)) {
            try { Long.parseLong(req.getValue().trim()); }
            catch (Exception e) { throw new IllegalArgumentException("default-rest-tenant must be numeric"); }
        }
        String admin = SecurityContextHolder.getContext().getAuthentication().getName();
        settingsService.set(key, req.getValue(), req.isSecret(), admin);
        if (SettingsRegistry.effectClassOf(key) == EffectClass.REQUIRES_RESTART) {
            settingsService.markRestartPending();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        // Quoted-lowercase aliases: H2 upper-cases unquoted labels (SKEY, OLD_VALUE, ...),
        // which would break the documented lowercase JSON contract the frontend relies on.
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT skey AS \"skey\", old_value AS \"old_value\", new_value AS \"new_value\", " +
                "changed_by AS \"changed_by\", changed_at AS \"changed_at\" " +
                "FROM settings_audit ORDER BY changed_at DESC FETCH FIRST 200 ROWS ONLY"));
    }
}
