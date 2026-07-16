package uz.greenwhite.ops;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OpsPageController {
    @GetMapping("/ops")
    public String page() { return "forward:/ops/index.html"; }
}
