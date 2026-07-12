package uz.greenwhite.gateway.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.greenwhite.gateway.logs.LogQueryResult;
import uz.greenwhite.gateway.logs.LogQueryService;

import java.util.Map;

/**
 * Read-only admin proxy over Loki. Under the /qosid/admin/api/** chain, so it is
 * session-authenticated + CSRF-protected like the rest of the control plane.
 */
@RestController
@RequestMapping("/qosid/admin/api/logs")
@RequiredArgsConstructor
public class LogQueryController {

    private final LogQueryService logQueryService;

    @GetMapping
    public ResponseEntity<LogQueryResult> query(
            @RequestParam String source,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String container,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(logQueryService.query(source, from, to, q, container, level, limit));
    }

    @GetMapping("/labels")
    public ResponseEntity<Map<String, Object>> labels(@RequestParam String name) {
        return ResponseEntity.ok(Map.of("values", logQueryService.labelValues(name)));
    }
}
