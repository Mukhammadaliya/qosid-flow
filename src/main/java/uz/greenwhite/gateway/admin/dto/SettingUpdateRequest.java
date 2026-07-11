package uz.greenwhite.gateway.admin.dto;

import lombok.Data;

@Data
public class SettingUpdateRequest {
    private String value;
    private boolean secret;
}
