package uz.greenwhite.gateway.kafka.consumer.oracle;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.enums.RequestStatus;
import uz.greenwhite.gateway.model.kafka.ResponseMessage;
import uz.greenwhite.gateway.model.ResponseSaveRequest;
import uz.greenwhite.gateway.source.ResponseSinkClient;
import uz.greenwhite.gateway.state.RequestStateService;
import uz.greenwhite.gateway.storage.ResponseStorageService;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class OracleResponseConsumer {

    private final ResponseSinkClient responseSinkClient;
    private final RequestStateService requestStateService;
    private final RetryProperties retryProperties;
    private final GatewayMetrics metrics;
    private final ResponseStorageService responseStorageService;

    public OracleResponseConsumer(
            ResponseSinkClient responseSinkClient,
            RequestStateService requestStateService,
            RetryProperties retryProperties,
            GatewayMetrics metrics,
            ResponseStorageService responseStorageService) {
        this.responseSinkClient = responseSinkClient;
        this.requestStateService = requestStateService;
        this.retryProperties = retryProperties;
        this.metrics = metrics;
        this.responseStorageService = responseStorageService;
    }

    @KafkaListener(
            id = "oracleResponseConsumer",
            topics = "${gateway.kafka.topics.oracle-response}",
            groupId = "${gateway.kafka.group-id}",
            containerFactory = "oracleResponseConsumerFactory"
    )
    public void consumeResponse(ConsumerRecord<String, ResponseMessage> record, Acknowledgment ack) {
        String key = record.key();
        ResponseMessage message = record.value();

        log.info("[ORACLE] Received response: {} [partition={}, offset={}]",
                key, record.partition(), record.offset());

        try {
            if (message.getBody() != null) {
                responseStorageService.saveResponse(
                        message.getCompositeId(), message.getBody(),
                        message.getHttpStatus(), message.getContentType());
                requestStateService.updateHasResult(message.getCompositeId(), true);
            }

            Timer.Sample saveSample = Timer.start(metrics.getRegistry());
            boolean saved = saveWithRetry(message);
            saveSample.stop(metrics.getOracleSaveTimer());

            if (saved) {
                metrics.getOracleSaveSuccess().increment();
                requestStateService.updateStatus(key, RequestStatus.COMPLETED);
                log.info("[ORACLE] Response saved successfully: {}", key);
            } else {
                metrics.getOracleSaveError().increment();
                saveErrorResponse(message, "Failed to save response after " +
                        retryProperties.getMaxAttempts() + " attempts");
                requestStateService.updateStatus(key, RequestStatus.FAILED);
                log.error("[ORACLE] Failed to save response after retries: {}", key);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("[ORACLE] Error processing response {}: {}", key, e.getMessage(), e);
            metrics.getOracleSaveError().increment();
            saveErrorResponse(message, e.getMessage());
            requestStateService.updateStatus(key, RequestStatus.FAILED);
            ack.acknowledge();
        }
    }

    private boolean saveWithRetry(ResponseMessage message) {
        String key = message.getCompositeId();
        for (int attempt = 1; attempt <= retryProperties.getMaxAttempts(); attempt++) {
            try {
                var saveRequest = buildSaveRequest(message);
                boolean saved = responseSinkClient.saveResponse(saveRequest);
                if (saved) return true;
                log.warn("[ORACLE] Save returned false for {}: attempt {}/{}", key, attempt, retryProperties.getMaxAttempts());
            } catch (Exception e) {
                log.warn("[ORACLE] Save failed for {}: attempt {}/{} - {}", key, attempt, retryProperties.getMaxAttempts(), e.getMessage());
            }
            if (attempt < retryProperties.getMaxAttempts()) {
                metrics.getOracleSaveRetry().increment();
                sleepBeforeRetry();
            }
        }
        return false;
    }

    private void saveErrorResponse(ResponseMessage message, String errorMessage) {
        try {
            var errorRequest = ResponseSaveRequest.builder()
                    .companyId(message.getCompanyId())
                    .requestId(message.getRequestId())
                    .response(null)
                    .errorMessage(errorMessage)
                    .build();
            responseSinkClient.saveResponse(errorRequest);
        } catch (Exception e) {
            log.error("[ORACLE] Failed to save error response: {} - {}", message.getCompositeId(), e.getMessage());
        }
    }

    private ResponseSaveRequest buildSaveRequest(ResponseMessage message) {
        ResponseSaveRequest.ResponseData responseData;
        if (message.isSuccess()) {
            byte[] body = message.getBody();
            String bodyAsString = body != null ? new String(body, StandardCharsets.UTF_8) : null;
            responseData = ResponseSaveRequest.ResponseData.builder()
                    .status(message.getHttpStatus()).body(bodyAsString).build();
        } else {
            responseData = ResponseSaveRequest.ResponseData.builder()
                    .status(message.getHttpStatus()).body(message.getErrorMessage()).build();
        }
        return ResponseSaveRequest.builder()
                .companyId(message.getCompanyId())
                .requestId(message.getRequestId())
                .response(responseData)
                .errorMessage(message.getErrorMessage())
                .build();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(retryProperties.getIntervalMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
