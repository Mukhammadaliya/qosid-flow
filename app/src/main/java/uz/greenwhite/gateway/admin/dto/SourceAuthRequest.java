package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class SourceAuthRequest {
    private String type;               // basic | apikey | oauth2
    private String basicUsername;
    private String basicPassword;      // secret
    private String apikeyHeader;
    private String apikeyValue;        // secret
    private String oauth2TokenUrl;
    private String oauth2ClientId;
    private String oauth2ClientSecret; // secret
    private String oauth2Scope;
    private String oauth2CacheTtlSeconds;
}
