package uz.greenwhite.gateway.kafka.consumer.oracle;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.config.RetryProperties;
import uz.greenwhite.gateway.http.HttpRequestService;
import uz.greenwhite.gateway.kafka.producer.RequestProducer;
import uz.greenwhite.gateway.metrics.GatewayMetrics;
import uz.greenwhite.gateway.model.kafka.RequestMessage;
import uz.greenwhite.gateway.oauth2.OAuth2ProviderService;
import uz.greenwhite.gateway.state.RequestStateService;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OracleRequestConsumerTest {

    @Mock HttpRequestService httpRequestService;
    @Mock RequestStateService requestStateService;
    @Mock RequestProducer requestProducer;
    @Mock RetryProperties retryProperties;
    @Mock ThreadPoolTaskExecutor httpExecutor;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) GatewayMetrics metrics;
    @Mock KafkaProperties kafkaProperties;
    @Mock OAuth2ProviderService oAuth2ProviderService;
    @Mock Acknowledgment ack;

    @Test
    void redisDownAtGateNacksInsteadOfAcking() {
        when(requestStateService.isCompleted("1:2"))
                .thenThrow(new RuntimeException("Unable to connect to Redis"));

        OracleRequestConsumer consumer = new OracleRequestConsumer(
                httpRequestService, requestStateService, requestProducer,
                retryProperties, httpExecutor, metrics, kafkaProperties, oAuth2ProviderService);

        RequestMessage message = RequestMessage.builder().companyId(1L).requestId(2L).build();
        consumer.consumeRequest(new ConsumerRecord<>("t", 0, 0L, "1:2", message), ack);

        verify(ack).nack(any(Duration.class));
        verify(ack, never()).acknowledge();
        verify(requestProducer, never()).sendOracleRequest(any());
        verify(requestProducer, never()).sendToOracleDlq(any());
    }
}
