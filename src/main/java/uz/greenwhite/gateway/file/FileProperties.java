package uz.greenwhite.gateway.file;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the S3-compatible object storage used for file uploads.
 * Bound to {@code gateway.s3.*} in application.yml. Starts disabled; enable
 * per environment via the {@code S3_ENABLED} env var.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.s3")
public class FileProperties {

    private boolean enabled = false;
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String tempBucket;
    private String permanentBucket;
    private int linkExpirationHours = 24;
}
