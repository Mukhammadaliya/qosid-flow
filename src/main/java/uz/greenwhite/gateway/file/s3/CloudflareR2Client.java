package uz.greenwhite.gateway.file.s3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import uz.greenwhite.gateway.file.FileProperties;

import java.net.URI;
import java.time.Duration;
import java.io.InputStream;

/**
 * Cloudflare R2 uploader using the AWS S3 SDK. Only registered when
 * {@code gateway.s3.cloudflare-r2.enabled=true}.
 *
 * <p>Object keys are sharded by the SHA — {@code <first2>/<next2>/<full-sha>}
 * — to avoid hotspotting a single bucket prefix. Both the temp and permanent
 * buckets share the same client; callers pass the target bucket name.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.s3.cloudflare-r2.enabled", havingValue = "true")
public class CloudflareR2Client {

    private final S3Client client;
    private final S3Presigner presigner;
    private final FileProperties.R2Config config;

    public CloudflareR2Client(FileProperties properties) {
        this.config = properties.getCloudflareR2();

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                config.getAccessKey(), config.getSecretKey());
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);

        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();

        this.client = S3Client.builder()
                .endpointOverride(URI.create(config.getEndpoint()))
                .credentialsProvider(credentialsProvider)
                .region(Region.of("auto"))
                .serviceConfiguration(serviceConfig)
                .overrideConfiguration(override -> override
                        .apiCallTimeout(Duration.ofMinutes(5))
                        .apiCallAttemptTimeout(Duration.ofMinutes(5)))
                .build();

        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(config.getEndpoint()))
                .credentialsProvider(credentialsProvider)
                .region(Region.of("auto"))
                .serviceConfiguration(serviceConfig)
                .build();

        log.info("Cloudflare R2 client initialized: endpoint={}", config.getEndpoint());
    }

    /**
     * SHA-keyed dedup upload. Bucket name comes from the caller so the same
     * client serves both temp and permanent paths.
     */
    public void uploadObject(String bucket, String sha, InputStream stream, String contentType, long fileSize) {
        try {
            if (checkObjectExist(bucket, sha)) {
                log.debug("R2 object already exists, skipping upload: bucket={}, sha={}", bucket, sha);
                return;
            }
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(getObjectPath(sha))
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(stream, fileSize));
            log.debug("R2 upload complete: bucket={}, sha={}, size={}", bucket, sha, fileSize);
        } catch (Exception e) {
            log.error("Error uploading to R2: bucket={}, sha={}, msg={}", bucket, sha, e.getMessage(), e);
            throw new RuntimeException("Error while uploading file to CloudflareR2", e);
        }
    }

    public boolean checkObjectExist(String bucket, String sha) {
        try {
            HeadObjectResponse head = client.headObject(b -> b.bucket(bucket).key(getObjectPath(sha)));
            return head.sdkHttpResponse().isSuccessful() && head.contentLength() != null && head.contentLength() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generate a presigned GET URL valid for {@link FileProperties.R2Config#getLinkExpirationHours()}.
     */
    public String generatePresignedUrl(String bucket, String sha) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(getObjectPath(sha))
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(config.getLinkExpirationHours()))
                .getObjectRequest(getRequest)
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
        return presigned.url().toString();
    }

    /** {@code <first2>/<next2>/<full-sha>} sharding — spreads keys across prefixes. */
    public String getObjectPath(String sha) {
        return sha.substring(0, 2) + "/" + sha.substring(2, 4) + "/" + sha;
    }

    public String getTempBucket() {
        return config.getTempBucket();
    }

    public String getPermanentBucket() {
        return config.getPermanentBucket();
    }
}
