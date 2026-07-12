package uz.greenwhite.gateway.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.greenwhite.gateway.model.kafka.DlqMessage;

/**
 * Writes each DLQ failure as a single-line JSON record to the dedicated
 * {@code dlq-audit} logger (routed to {@code dlq.log} in logback-spring.xml,
 * additivity=false). Promtail labels that file {@code stream="dlq"} and the
 * Errors UI reads it back through the Loki proxy.
 *
 * Best-effort: a serialization or logging failure is swallowed so it can never
 * break the DLQ consumer or the Kafka MANUAL ack.
 */
@Component
@RequiredArgsConstructor
public class DlqAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("dlq-audit");
    private static final Logger LOG = LoggerFactory.getLogger(DlqAuditLogger.class);

    private final ObjectMapper objectMapper;

    public void record(DlqMessage message) {
        try {
            AUDIT.error(toJson(message));
        } catch (Exception e) {
            LOG.warn("DLQ audit log skipped: {}", e.getMessage());
        }
    }

    String toJson(DlqMessage message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(message);
    }
}
