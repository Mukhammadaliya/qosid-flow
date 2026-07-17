package uz.greenwhite.gateway.kafka.consumer.api;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.callback.CallbackService;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseStorageService;
import uz.greenwhite.gateway.storage.StorageWriteException;

@Slf4j
@Service
public class ApiResponseConsumer {

    private final RequestStateService requestStateService;
    private final GatewayMetrics metrics;
    private final ResponseStorageService responseStorageService;
    private final CallbackService callbackService;

    public ApiResponseConsumer(
            RequestStateService requestStateService,
            GatewayMetrics metrics,
            ResponseStorageService responseStorageService,
            CallbackService callbackService) {
        this.requestStateService = requestStateService;
        this.metrics = metrics;
        this.responseStorageService = responseStorageService;
        this.callbackService = callbackService;
    }

    @KafkaListener(
            id = "apiResponseConsumer",
            topics = "${gateway.kafka.topics.api-response}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "apiResponseConsumerFactory"
    )
    public void consumeResponse(ConsumerRecord<String, ResponseMessage> record, Acknowledgment ack) {
        String key = record.key();
        ResponseMessage message = record.value();

        log.info("[API] Received response: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        try {
            if (message.getBody() != null) {
                try {
                    responseStorageService.saveResponse(
                            message.getCompositeId(), message.getBody(),
                            message.getHttpStatus(), message.getContentType());
                    requestStateService.updateHasResult(message.getCompositeId(), true);
                } catch (StorageWriteException e) {
                    log.error("[API] RocksDB save failed for {} — /result will 404, continuing pipeline: {}",
                            key, e.getMessage());
                }
            }

            callbackService.sendCallbackIfNeeded(message);

            RequestStatus status = message.isSuccess() ? RequestStatus.COMPLETED : RequestStatus.FAILED;
            requestStateService.updateStatus(key, status);
            log.info("[API] Response processed: {} -> {}", key, status);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("[API] Error processing response {}: {}", key, e.getMessage(), e);
            requestStateService.updateStatus(key, RequestStatus.FAILED);
            ack.acknowledge();
        }
    }
}
