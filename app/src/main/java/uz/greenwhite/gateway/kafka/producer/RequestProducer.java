package uz.greenwhite.gateway.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaProperties kafkaProperties;

    // ==================== ORACLE ====================

    public CompletableFuture<SendResult<String, Object>> sendOracleRequest(RequestMessage message) {
        return send(kafkaProperties.getTopics().getOracleRequest(), message.getCompositeId(), message, "Oracle request");
    }

    public CompletableFuture<SendResult<String, Object>> sendOracleResponse(ResponseMessage message) {
        return send(kafkaProperties.getTopics().getOracleResponse(), message.getCompositeId(), message, "Oracle response");
    }

    public CompletableFuture<SendResult<String, Object>> sendToOracleDlq(DlqMessage message) {
        log.warn("Sending to Oracle DLQ: {} - reason: {}", message.getCompositeId(), message.getFailureReason());
        return send(kafkaProperties.getTopics().getOracleDlq(), message.getCompositeId(), message, "Oracle DLQ");
    }

    // ==================== API ====================

    public CompletableFuture<SendResult<String, Object>> sendApiRequest(RequestMessage message) {
        return send(kafkaProperties.getTopics().getApiRequest(), message.getCompositeId(), message, "API request");
    }

    public CompletableFuture<SendResult<String, Object>> sendApiResponse(ResponseMessage message) {
        return send(kafkaProperties.getTopics().getApiResponse(), message.getCompositeId(), message, "API response");
    }

    public CompletableFuture<SendResult<String, Object>> sendToApiDlq(DlqMessage message) {
        log.warn("Sending to API DLQ: {} - reason: {}", message.getCompositeId(), message.getFailureReason());
        return send(kafkaProperties.getTopics().getApiDlq(), message.getCompositeId(), message, "API DLQ");
    }

    // ==================== HELPER ====================

    private CompletableFuture<SendResult<String, Object>> send(String topic, String key, Object message, String label) {
        log.debug("Sending {} to Kafka: {}", label, key);
        return kafkaTemplate.send(topic, key, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to send {} {}: {}", label, key, ex.getMessage());
                    } else {
                        log.info("{} sent successfully: {} [partition={}, offset={}]",
                                label, key,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
