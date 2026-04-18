package uz.greenwhite.gateway.model.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.greenwhite.gateway.util.StringToMapDeserializer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestMessage {

    @JsonProperty("company_id")
    private Long companyId;

    @JsonProperty("request_id")
    private Long requestId;

    @JsonProperty("filial_id")
    private Long filialId;

    @JsonProperty("endpoint_id")
    private Long endpointId;

    @JsonProperty("base_url")
    private String baseUrl;

    private String uri;
    private String params;
    private String method;

    @JsonProperty("headers")
    @JsonDeserialize(using = StringToMapDeserializer.class)
    private Map<String, String> headers;

    private String body;

    @JsonProperty("oauth2_provider")
    private String oauth2Provider;

    @JsonProperty("callback_procedure")
    private String callbackProcedure;

    @JsonProperty("project_code")
    private String projectCode;

    @JsonProperty("source_table")
    private String sourceTable;

    @JsonProperty("source_id")
    private Long sourceId;

    private LocalDateTime createdAt;

    private String source;              // "ORACLE" | "API"

    @JsonProperty("callback_url")
    private String callbackUrl;         // X-Callback-Url header'dan (nullable)

    @JsonProperty("file_references")
    private List<String> fileReferences; // S3'ga yuklangan fayllar SHA ro'yxati (nullable)

    @JsonProperty("client_ip")
    private String clientIp;            // Statistika uchun

    @JsonProperty("content_type")
    private String contentType;         // Original content-type

    @JsonProperty("biruni_upload_mode")
    private String biruniUploadMode;    // null = normal, "param"/"alone" = multipart fallback to Biruni DB

    @JsonProperty("file_parts")
    private List<FilePart> fileParts;   // File data for multipart fallback (only when S3 failed)

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilePart {
        private String fieldName;       // e.g. "file[0]"
        private String fileName;        // e.g. "IMG_11.png"
        private String contentType;     // e.g. "image/png"
        private byte[] data;            // raw file bytes
    }

    public String getCompositeId() {
        return companyId + ":" + requestId;
    }
}