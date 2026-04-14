package uz.greenwhite.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.proxy")
public class ProxyProperties {

    /**
     * URI prefix for the proxy endpoint (e.g. /qosid/queue).
     * Stripped when forwarding the request upstream.
     */
    private String prefix = "/qosid/queue";

    /**
     * Application context path (e.g. /qosid).
     * Used for building status/result URLs.
     */
    private String contextPath = "/qosid";
}
