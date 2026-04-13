package uz.greenwhite.gateway.file.s3;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.HttpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.file.FileProperties;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO-backed file uploader. Only registered when
 * {@code gateway.s3.minio.enabled=true}. Uses the SHA-256 of the file as the
 * object key, so uploads are automatically deduplicated — an existing key is
 * left untouched.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.s3.minio.enabled", havingValue = "true")
public class MinIOClient {

    private static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    private final MinioClient client;
    private final String bucket;

    public MinIOClient(FileProperties properties) {
        FileProperties.MinioConfig config = properties.getMinio();
        this.bucket = config.getBucket();

        try {
            this.client = MinioClient.builder()
                    .endpoint(config.getEndpoint())
                    .credentials(config.getAccessKey(), config.getSecretKey())
                    .httpClient(HttpUtils.disableCertCheck(
                            HttpUtils.newDefaultHttpClient(TIMEOUT_MS, TIMEOUT_MS, TIMEOUT_MS)))
                    .build();

            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                throw new IllegalStateException("MinIO bucket does not exist: " + bucket);
            }

            log.info("MinIO client initialized: endpoint={}, bucket={}", config.getEndpoint(), bucket);
        } catch (Exception e) {
            log.error("Failed to initialize MinIO client: {}", e.getMessage(), e);
            throw new IllegalStateException("MinIO client initialization failed", e);
        }
    }

    /** Upload via SHA-keyed dedup — no-op if an object with the same SHA already exists. */
    public void uploadObject(InputStream stream, String sha, String contentType, long fileSize) {
        try {
            if (checkObjectExist(sha)) {
                log.debug("MinIO object already exists, skipping upload: sha={}", sha);
                return;
            }
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(sha)
                    .stream(stream, fileSize, -1)
                    .contentType(contentType)
                    .build());
            log.debug("MinIO upload complete: sha={}, size={}", sha, fileSize);
        } catch (Exception e) {
            log.error("Error uploading to MinIO: sha={}, msg={}", sha, e.getMessage(), e);
            throw new RuntimeException("Error while uploading file to MinIO", e);
        }
    }

    public boolean checkObjectExist(String sha) {
        try {
            return client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(sha)
                    .build()) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
