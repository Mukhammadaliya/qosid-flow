package uz.greenwhite.gateway.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class SettingsRecord {
    private String key;
    private String value;
    private boolean secret;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
