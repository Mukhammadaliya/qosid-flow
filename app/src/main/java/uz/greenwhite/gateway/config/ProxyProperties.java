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

    /**
     * Max size (bytes) of a NON-multipart request body accepted by {@code ProxyController}.
     * Bodies above this are refused with HTTP 413 before being buffered; {@code <= 0} disables
     * the check (operator escape hatch).
     *
     * <p>Default 20 MiB = the Kafka message ceiling already configured for this deployment
     * (producer {@code max.request.size} and consumer {@code fetch.max.bytes} are both
     * {@code 20971520}). The body is carried verbatim as a JSON string field of
     * {@code RequestMessage}, so the serialized record is never smaller than the body — a body
     * over 20 MiB therefore already fails at the producer today. Choosing the same number means
     * the gate cannot reject a request that currently succeeds; it only stops an oversize body
     * from being buffered on a Tomcat worker thread before that inevitable failure.</p>
     *
     * <p>This limit deliberately does NOT apply to the {@code BiruniUpload} multipart branch —
     * real S3 uploads stay governed by {@code spring.servlet.multipart.max-file-size} (50MB) /
     * {@code max-request-size} (52MB), so production uploads are unaffected.</p>
     */
    private long maxBodySize = 20L * 1024 * 1024;
}
