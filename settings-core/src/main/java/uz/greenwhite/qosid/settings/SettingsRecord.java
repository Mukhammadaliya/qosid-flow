package uz.greenwhite.qosid.settings;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingsRecord {
    private String key;
    private String value;
    private boolean secret;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
