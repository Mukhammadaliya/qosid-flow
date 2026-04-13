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

    /**
     * Validates each file's content type, uploads it, and returns the SHA list
     * in the same order. The SHA list is what the pipeline persists on
     * {@code RequestMessage.fileReferences}.
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
