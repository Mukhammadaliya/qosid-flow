package uz.greenwhite.gateway.kafka.consumer.oracle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.model.kafka.DlqMessage;
import uz.greenwhite.gateway.notification.NotificationService;

@Slf4j
@Service
@RequiredArgsConstructor
public class OracleDlqConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            id = "oracleDlqConsumer",
            topics = "${gateway.kafka.topics.oracle-dlq}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "dlqConsumerFactory"
    )
    public void consumeDlq(ConsumerRecord<String, DlqMessage> record, Acknowledgment ack) {
        String key = record.key();
        DlqMessage message = record.value();

        log.info("[ORACLE-DLQ] Message received: {} [partition={}, offset={}]", key, record.partition(), record.offset());

        try {
            notificationService.sendDlqAlert(message);
        } catch (Exception e) {
            log.error("[ORACLE-DLQ] Failed to process message {}: {}", key, e.getMessage());
        }

        ack.acknowledge();
    }
}
