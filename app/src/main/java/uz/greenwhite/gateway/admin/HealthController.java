package uz.greenwhite.gateway.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Open (permitAll) admin-API health probe. Pulled forward from the settings/audit
 * API task because {@code SecurityIsolationTest} needs a real permitAll endpoint
 * under {@code /qosid/admin/api/**} to prove the admin chain's login/health
 * allowlist actually reaches a handler (not just a coincidental 404).
 */
@RestController
@RequestMapping("/qosid/admin/api")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
