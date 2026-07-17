package uz.greenwhite.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Control-plane (settings DB + admin) bootstrap config. Lives in yaml, NOT in the settings DB. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.settings")
public class SettingsProperties {

    /** H2 path/URL fragment. Default file mode; tests override with mem:. */
    private String dbPath = "./data/settings";

    /** AES master key (base64 or raw). Blank in dev → secrets disabled. */
    private String encryptionKey = "";

    /** Admin session TTL in minutes. */
    private long sessionTtlMinutes = 480;
}
