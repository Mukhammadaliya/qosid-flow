package uz.greenwhite.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.proxy")
public class ProxyProperties {

    /**
     * URI prefix for the proxy endpoint (e.g. /qosid/api/v1/queue).
     * Stripped when forwarding the request upstream.
     */
    private String prefix = "/qosid/api/v1/queue";

    /**
     * Application context path (e.g. /qosid).
     * Used for building status/result URLs.
     */
    private String contextPath = "/qosid";

    /** Peers (remote addresses) whose X-Forwarded-For / X-Real-IP headers are trusted. */
    private List<String> trustedProxies = new ArrayList<>();
}
