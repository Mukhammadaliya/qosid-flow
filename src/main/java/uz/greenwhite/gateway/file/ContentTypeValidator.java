package uz.greenwhite.gateway.file;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.util.ArrayList;
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

    private final Environment environment;
    private List<String> allowedContentTypes;

    public ContentTypeValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void init() {
        allowedContentTypes = new ArrayList<>();
        int i = 0;
        while (true) {
            String val = environment.getProperty("gateway.security.content-types[" + i + "]");
            if (val == null) break;
            allowedContentTypes.add(val.trim().toLowerCase());
            i++;
        }
        log.info("ContentTypeValidator: {} allowed content types loaded", allowedContentTypes.size());
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
