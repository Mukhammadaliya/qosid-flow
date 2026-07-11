package uz.greenwhite.gateway.admin;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import uz.greenwhite.gateway.admin.dto.SettingUpdateRequest;
import uz.greenwhite.gateway.settings.SettingsRecord;
import uz.greenwhite.gateway.settings.SettingsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/qosid/admin/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final JdbcTemplate jdbc;

    public SettingsController(SettingsService settingsService, @Qualifier("settingsJdbcTemplate") JdbcTemplate jdbc) {
        this.settingsService = settingsService;
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<List<SettingsRecord>> getAll() {
        return ResponseEntity.ok(settingsService.getAllMasked());
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> update(@PathVariable String key, @RequestBody SettingUpdateRequest req) {
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
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT skey, old_value, new_value, changed_by, changed_at " +
                "FROM settings_audit ORDER BY changed_at DESC FETCH FIRST 200 ROWS ONLY"));
    }
}
