package uz.greenwhite.gateway.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.util.List;

/**
 * Enforces the MIME whitelist defined by {@code gateway.security.content-types}.
 *
 * <p>Values with parameters (e.g. {@code "image/jpeg; charset=utf-8"}) are normalised
 * — only the {@code type/subtype} portion is compared, case-insensitively.</p>
 */
@Slf4j
@Component
public class ContentTypeValidator {

    private final List<String> allowedContentTypes;

    public ContentTypeValidator(@Value("${gateway.security.content-types}") List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public boolean isAllowed(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.split(";")[0].trim().toLowerCase();
        return allowedContentTypes.contains(normalized);
    }

    public void validate(String contentType) {
        if (!isAllowed(contentType)) {
            throw new RequestValidationException("Unsupported content type: " + contentType);
        }
    }
}
