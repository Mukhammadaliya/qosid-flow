package uz.greenwhite.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.kafka")
public class KafkaProperties {

    private String bootstrapServers;
    private String groupId;
    private Topics topics = new Topics();

    @PostConstruct
    public void validate() {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("gateway.kafka.bootstrap-servers must be configured");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("gateway.kafka.group-id must be configured");
        }
        topics.validate();

        log.info("Kafka config: servers={}, groupId={}, oracle=[{}, {}, {}], api=[{}, {}, {}]",
                bootstrapServers, groupId,
                topics.oracleRequest, topics.oracleResponse, topics.oracleDlq,
                topics.apiRequest, topics.apiResponse, topics.apiDlq);
    }

    @Getter
    @Setter
    public static class Topics {
        private String oracleRequest;
        private String oracleResponse;
        private String oracleDlq;
        private String apiRequest;
        private String apiResponse;
        private String apiDlq;

        void validate() {
            requireNonBlank(oracleRequest, "gateway.kafka.topics.oracle-request");
            requireNonBlank(oracleResponse, "gateway.kafka.topics.oracle-response");
            requireNonBlank(oracleDlq, "gateway.kafka.topics.oracle-dlq");
            requireNonBlank(apiRequest, "gateway.kafka.topics.api-request");
            requireNonBlank(apiResponse, "gateway.kafka.topics.api-response");
            requireNonBlank(apiDlq, "gateway.kafka.topics.api-dlq");
        }

        private void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must be configured");
            }
        }
    }
}
