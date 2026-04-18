package uz.greenwhite.gateway.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.file.s3.CloudflareR2Client;
import uz.greenwhite.gateway.file.s3.MinIOClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates and routes uploaded files to the right S3 backend.
 *
 * <p>Routing rule: {@link ServletFileUploadEntity.FileData#getBucket()} {@code == null}
 * → MinIO; otherwise → Cloudflare R2 targeting that bucket. Both backends are
 * optional — missing a configured backend only matters if a caller actually
 * tries to use it (then an {@link IllegalStateException} is thrown).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final ContentTypeValidator contentTypeValidator;
    private final Optional<MinIOClient> minIOClient;
    private final Optional<CloudflareR2Client> r2Client;

    private static final int MAX_RETRY = 3;

    /**
     * Validates each file's content type, uploads to S3, and returns the SHA list.
     * @return SHA list (same order as input files)
     * @throws RuntimeException if S3 upload fails after all retries
     */
    public List<String> uploadFiles(List<ServletFileUploadEntity.FileData> files) {
        List<String> shaList = new ArrayList<>(files.size());
        for (ServletFileUploadEntity.FileData file : files) {
            contentTypeValidator.validate(file.getContentType());
            uploadFile(file);
            shaList.add(file.getSha());
        }
        return shaList;
    }

    /**
     * Try S3 upload with retry. Returns true if all files uploaded successfully.
     * On failure after 3 retries, returns false (caller should fallback to DB via Biruni).
     */
    public boolean tryUploadFiles(List<ServletFileUploadEntity.FileData> files) {
        for (ServletFileUploadEntity.FileData file : files) {
            contentTypeValidator.validate(file.getContentType());
            if (!tryUploadFileWithRetry(file)) {
                return false;
            }
        }
        return true;
    }

    private boolean tryUploadFileWithRetry(ServletFileUploadEntity.FileData file) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                uploadFile(file);
                return true;
            } catch (Exception e) {
                log.warn("S3 upload failed for sha={}, attempt {}/{}: {}",
                        file.getSha(), attempt, MAX_RETRY, e.getMessage());
                if (attempt < MAX_RETRY) {
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        log.error("S3 upload failed after {} retries for sha={}, falling back to DB",
                MAX_RETRY, file.getSha());
        return false;
    }

    private void uploadFile(ServletFileUploadEntity.FileData file) {
        if (file.getBucket() == null) {
            minIOClient.orElseThrow(() -> new IllegalStateException("MinIO is not configured"))
                    .uploadObject(file.getInputStream(), file.getSha(), file.getContentType(), file.getSize());
        } else {
            r2Client.orElseThrow(() -> new IllegalStateException("Cloudflare R2 is not configured"))
                    .uploadObject(file.getBucket(), file.getSha(),
                            file.getInputStream(), file.getContentType(), file.getSize());
        }
    }
}
