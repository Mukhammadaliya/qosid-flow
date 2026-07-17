package uz.greenwhite.gateway.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final KafkaProperties kafkaProperties;
    private final ConcurrencyProperties concurrencyProperties;

    // ==================== ADMIN CLIENT ====================

    @Bean
    public AdminClient kafkaAdminClient() {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        return AdminClient.create(props);
    }

    // ==================== TOPICS ====================

    @Bean
    public NewTopic oracleRequestTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getOracleRequest())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic oracleResponseTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getOracleResponse())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic oracleDlqTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getOracleDlq())
                .partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic apiRequestTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getApiRequest())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic apiResponseTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getApiResponse())
                .partitions(concurrencyProperties.getTopicPartitions())
                .replicas(1).build();
    }

    @Bean
    public NewTopic apiDlqTopic() {
        return TopicBuilder.name(kafkaProperties.getTopics().getApiDlq())
                .partitions(3).replicas(1).build();
    }

    // ==================== ERROR HANDLER ====================

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    log.error("Kafka message processing failed permanently — skipping. " +
                                    "topic={}, partition={}, offset={}, key={}, error={}",
                            record.topic(), record.partition(), record.offset(),
                            record.key(), exception.getMessage(), exception);
                },
                new FixedBackOff(1000L, 2L)
        );
        errorHandler.addNotRetryableExceptions(
                org.apache.kafka.common.errors.SerializationException.class,
                org.springframework.kafka.support.serializer.DeserializationException.class
        );
        return errorHandler;
    }

    // ==================== CONSUMER FACTORY ====================

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getGroupId());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "uz.greenwhite.gateway.*");
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    // ==================== ORACLE CONTAINER FACTORIES ====================

    @Bean("oracleRequestConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> oracleRequestConsumerFactory() {
        return buildFactory(concurrencyProperties.getOracleMin(), "Oracle request");
    }

    @Bean("oracleResponseConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> oracleResponseConsumerFactory() {
        return buildFactory(concurrencyProperties.getOracleMin(), "Oracle response");
    }

    // ==================== API CONTAINER FACTORIES ====================

    @Bean("apiRequestConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> apiRequestConsumerFactory() {
        return buildFactory(concurrencyProperties.getApiMin(), "API request");
    }

    @Bean("apiResponseConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> apiResponseConsumerFactory() {
        return buildFactory(concurrencyProperties.getApiMin(), "API response");
    }

    // ==================== DLQ CONTAINER FACTORY ====================

    @Bean("dlqConsumerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> dlqListenerContainerFactory() {
        return buildFactory(1, "DLQ");
    }

    // ==================== HELPER ====================

    private ConcurrentKafkaListenerContainerFactory<String, Object> buildFactory(int concurrency, String label) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setIdleBetweenPolls(100);
        factory.setCommonErrorHandler(kafkaErrorHandler());
        log.info("{} consumer factory created with concurrency: {}", label, concurrency);
        return factory;
    }
}
