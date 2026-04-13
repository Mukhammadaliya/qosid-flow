package uz.greenwhite.gateway.file;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the S3-compatible object storage backends used for file
 * uploads. Bound to {@code gateway.s3.*} in application.yml. Each sub-config
 * starts disabled; enable per environment via env vars.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.s3")
public class FileProperties {

    private MinioConfig minio = new MinioConfig();
    private R2Config cloudflareR2 = new R2Config();

    @Getter
    @Setter
    public static class MinioConfig {
        private boolean enabled = false;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket = "gateway-files";
    }

    @Getter
    @Setter
    public static class R2Config {
        private boolean enabled = false;
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String tempBucket;
        private String permanentBucket;
        private int linkExpirationHours = 24;
    }
}
