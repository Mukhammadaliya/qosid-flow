package uz.greenwhite.gateway.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.file.s3.S3Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validates and uploads multipart files to S3-compatible storage with retry.
 *
 * <p>Default routing: {@link ServletFileUploadEntity.FileData#getBucket()} {@code == null}
 * uploads to the {@link S3Client#getPermanentBucket() permanent bucket}; non-null
 * bucket overrides that target. Fallback to multipart DB storage is handled by
 * the caller if {@link #tryUploadFiles(List)} returns false.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final ContentTypeValidator contentTypeValidator;
    private final Optional<S3Client> s3Client;

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
        S3Client s3 = s3Client.orElseThrow(
                () -> new IllegalStateException("S3 client is not configured"));
        String bucket = file.getBucket() != null ? file.getBucket() : s3.getPermanentBucket();
        s3.uploadObject(bucket, file.getSha(),
                file.getInputStream(), file.getContentType(), file.getSize());
    }
}
