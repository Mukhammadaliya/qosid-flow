package uz.greenwhite.gateway.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side-routing fallback: forward the SPA's navigation routes to the shell so React
 * Router can render them on reload/deep-link.
 *
 * Spring Boot 3.3 defaults to PathPatternParser, which forbids a captured segment after '**'
 * (a pattern of "/**" followed by "/{path}" throws PatternParseException). A TRAILING '**'
 * with no capture is allowed,
 * so we list the SPA route roots explicitly. '/' is served by Spring Boot's welcome-page handler
 * (static index.html) and is deliberately not mapped here. Asset paths (main-abc.js) and API
 * mappings (/qosid/**, /actuator/**) are not matched, so they are served/handled normally.
 * As later D-phases add top-level routes (/errors, /statistics, /logs) they extend this list.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/login", "/settings", "/settings/**", "/errors", "/errors/**", "/statistics"})
    public String forward() {
        return "forward:/index.html";
    }
}
