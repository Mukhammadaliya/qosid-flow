package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class AiConfigRequest {
    private String baseUrl;
    private String apiKey;
    private String model;
}
