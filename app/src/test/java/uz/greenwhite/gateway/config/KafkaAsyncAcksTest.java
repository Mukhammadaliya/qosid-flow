package uz.greenwhite.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer'lar ack'ni asinxron (CompletableFuture) yo'ldan chaqiradi, ya'ni ack'lar
 * offset tartibida kelmaydi. MANUAL rejimida bu asyncAcks=true bo'lmasa tartibsiz
 * commit'ga va jim xabar yo'qolishiga olib keladi. SAFETY-RULES 4: MANUAL o'zgarmaydi.
 */
@SpringBootTest(properties = {
        "gateway.polling.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "gateway.settings.db-path=mem:async_acks_test;DB_CLOSE_DELAY=-1",
        "gateway.settings.encryption-key=test-key-0123456789",
        "gateway.rocksdb.path=./target/test-data/rocksdb-async-acks"
})
class KafkaAsyncAcksTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("apiRequestConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> apiRequest;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("oracleRequestConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> oracleRequest;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("apiResponseConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> apiResponse;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("oracleResponseConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> oracleResponse;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("dlqConsumerFactory")
    ConcurrentKafkaListenerContainerFactory<String, Object> dlq;

    @Test
    void everyFactoryIsManualWithAsyncAcks() {
        for (ConcurrentKafkaListenerContainerFactory<String, Object> f :
                java.util.List.of(apiRequest, oracleRequest, apiResponse, oracleResponse, dlq)) {
            ContainerProperties p = f.getContainerProperties();
            assertThat(p.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
            assertThat(p.isAsyncAcks()).isTrue();
        }
    }
}
