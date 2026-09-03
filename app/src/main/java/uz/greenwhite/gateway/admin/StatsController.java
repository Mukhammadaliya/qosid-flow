package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.qosid.observability.stats.StatsQueryService;
import uz.greenwhite.qosid.observability.stats.StatsResult;

/**
 * Read-only admin proxy over Prometheus. Under the /qosid/admin/api/** chain,
 * so session-authenticated + CSRF-protected like the rest of the control plane.
 */
@RestController
@RequestMapping("/qosid/admin/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsQueryService statsQueryService;

    @GetMapping
    public ResponseEntity<StatsResult> query(
            @RequestParam String preset,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Integer step) {
        return ResponseEntity.ok(statsQueryService.query(preset, from, to, step));
    }
}
