package uz.greenwhite.gateway.file;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import lombok.AllArgsConstructor;
import lombok.Getter;
import uz.greenwhite.gateway.exception.RequestValidationException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * Parses a multipart upload into SHA-256-keyed {@link FileData} entries.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li>{@code "param"} — the form has a text field named {@code param} containing
 *   JSON with {@code "\u0000<index>"} placeholders. Each placeholder is replaced
 *   with the SHA of the corresponding file part ({@code index} is the trailing
 *   digits of the file part's form-field name).</li>
 *   <li>{@code "alone"} — file parts only, no JSON body.</li>
 * </ul>
 *
 * <p>Port of the reference implementation in {@code old/messagebroker} — simplified
 * to the subset qosid-flow needs (plain Servlet API, no Spring {@code MultipartFile}).</p>
 */
@Getter
public class ServletFileUploadEntity {

    private static final String MODE_PARAM = "param";
    private static final String MODE_ALONE = "alone";
    private static final String PARAM_FIELD = "param";

    private String updatedParam;
    private final List<FileData> files = new ArrayList<>();

    public ServletFileUploadEntity(HttpServletRequest request, String mode) {
        Collection<Part> parts = readParts(request);

        if (MODE_PARAM.equals(mode)) {
            this.updatedParam = readParamField(request);
        } else if (!MODE_ALONE.equals(mode)) {
            throw new RequestValidationException("Unknown upload mode: " + mode);
        }

        for (Part part : parts) {
            if (part.getSubmittedFileName() == null) {
                continue; // skip the "param" text field and any non-file parts
            }
            FileData file = buildFileData(part);
            files.add(file);

            if (MODE_PARAM.equals(mode)) {
                String index = extractTrailingDigits(part.getName());
                updatedParam = replacePlaceholder(updatedParam, index, file.getSha());
            }
        }
    }

    // ==================== PARSING ====================

    private static Collection<Part> readParts(HttpServletRequest request) {
        try {
            return request.getParts();
        } catch (Exception e) {
            throw new RequestValidationException("Failed to parse multipart request: " + e.getMessage());
        }
    }

    private static String readParamField(HttpServletRequest request) {
        try {
            Part paramPart = request.getPart(PARAM_FIELD);
            if (paramPart == null) {
                throw new RequestValidationException("Multipart 'param' field is required in param mode");
            }
            try (InputStream in = paramPart.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (RequestValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new RequestValidationException("Failed to read 'param' field: " + e.getMessage());
        }
    }

    private static FileData buildFileData(Part part) {
        try (InputStream in = part.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String sha = sha256Hex(bytes);
            String fileName = part.getSubmittedFileName() != null ? part.getSubmittedFileName() : "";
            return new FileData(sha, bytes, part.getContentType(), bytes.length, null, fileName);
        } catch (IOException e) {
            throw new RequestValidationException("Failed to read file part: " + e.getMessage());
        }
    }

    // ==================== HELPERS ====================

    /** Trailing digits of the form-field name — {@code "file0"} → {@code "0"}. */
    private static String extractTrailingDigits(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                out.append(name.charAt(i));
            }
        }
        return out.toString();
    }

    private static String replacePlaceholder(String param, String index, String sha) {
        // JSON.stringify encodes NULL char as literal "\u0000" (6 chars: \ u 0 0 0 0)
        return param.replace("\"\\u0000" + index + "\"", "\"" + sha + "\"");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ==================== FILE DATA ====================

    @Getter
    @AllArgsConstructor
    public static class FileData {
        private final String sha;
        private final byte[] data;
        private final String contentType;
        private final long size;
        /** null → MinIO, non-null → Cloudflare R2 targeting that bucket. */
        private final String bucket;
        private final String fileName;

        public InputStream getInputStream() {
            return new ByteArrayInputStream(data);
        }
    }
}
