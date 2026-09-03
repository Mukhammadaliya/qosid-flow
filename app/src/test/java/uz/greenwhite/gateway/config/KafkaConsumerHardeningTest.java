package uz.greenwhite.gateway.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.mapping.AbstractJavaTypeMapper;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import uz.greenwhite.gateway.model.kafka.RequestMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The consumer side of Kafka is configured **programmatically** in {@link KafkaConfig}, not from
 * `spring.kafka.consumer.*` in yaml: `KafkaConfig` declares its own `ConsumerFactory` bean, which
 * makes Spring Boot's auto-configured one back off, and every `@KafkaListener` names one of the
 * container factories built on top of it. Two consequences this test pins:
 *
 * <p><b>1. Type-id deserialization must stay restricted.</b> `JsonDeserializer` resolves the target
 * class from the `__TypeId__` record header, so a trusted-packages value of `"*"` would let anyone
 * who can write to an internal topic instantiate an arbitrary class on this JVM. The value that
 * actually applies is the one set here in code — the yaml key of the same name was inert, which is
 * exactly why it sat at `"*"` unnoticed and was read as an open RCE hole for months.
 *
 * <p><b>2. Settings written in the dead yaml block did nothing.</b> The 20 MiB fetch sizes added
 * for large payloads were among them, so they now live in the factory with everything else. This
 * test fails if a future edit moves consumer config back to the yaml block and assumes it applies.
 */
class KafkaConsumerHardeningTest {

    private static final int TWENTY_MIB = 20 * 1024 * 1024;

    private Map<String, Object> consumerProps() {
        KafkaProperties kafkaProperties = mock(KafkaProperties.class);
        when(kafkaProperties.getBootstrapServers()).thenReturn("localhost:19092");
        when(kafkaProperties.getGroupId()).thenReturn("gateway-group");
        ConcurrencyProperties concurrency = mock(ConcurrencyProperties.class);

        DefaultKafkaConsumerFactory<String, Object> factory =
                (DefaultKafkaConsumerFactory<String, Object>)
                        new KafkaConfig(kafkaProperties, concurrency).consumerFactory();
        return factory.getConfigurationProperties();
    }

    /** A JsonDeserializer configured exactly as the running consumers are. */
    private JsonDeserializer<Object> deserializerUnderTest() {
        JsonDeserializer<Object> deserializer = new JsonDeserializer<>();
        deserializer.configure(consumerProps(), false);
        return deserializer;
    }

    private RecordHeaders typeIdHeader(String className) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME,
                className.getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    @Test
    void trustedPackagesIsNotAWildcard() {
        assertThat(consumerProps().get(JsonDeserializer.TRUSTED_PACKAGES))
                .as("\"*\" trusts every class on the classpath — the whole point of this setting")
                .isNotNull()
                .isNotEqualTo("*")
                .asString()
                .startsWith("uz.greenwhite.gateway");
    }

    @Test
    void ourOwnMessageTypeStillDeserializes() {
        // `request_id` is the wire name (@JsonProperty) — the restriction must not be mistaken
        // for a working round trip, so this asserts a field that actually has to map.
        Object value = deserializerUnderTest().deserialize(
                "gateway.oracle.request",
                typeIdHeader(RequestMessage.class.getName()),
                "{\"tenantId\":100,\"request_id\":15073141}".getBytes(StandardCharsets.UTF_8));

        assertThat(value).isInstanceOf(RequestMessage.class);
        assertThat(((RequestMessage) value).getRequestId()).isEqualTo(15073141L);
    }

    /**
     * The attack this setting exists to stop: a record whose `__TypeId__` names a class outside our
     * own packages must be refused before Jackson instantiates anything.
     *
     * <p>The class chosen here is the textbook deserialization gadget, and deliberately not
     * something from `java.util` / `java.lang` — Spring trusts those two packages unconditionally,
     * so a `java.util.HashMap` type id proves nothing about our configuration.
     */
    @Test
    void aForeignTypeIdIsRefused() {
        JsonDeserializer<Object> deserializer = deserializerUnderTest();
        RecordHeaders foreign =
                typeIdHeader("org.springframework.context.support.ClassPathXmlApplicationContext");
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> deserializer.deserialize("gateway.oracle.request", foreign, payload))
                .as("an untrusted type id must not be instantiated")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not in the trusted packages");
    }

    /** 20 MiB payloads are a supported shape (`gateway.proxy.max-body-size`), so the fetch sizes
     *  must be on the factory that is actually used — not in the inert yaml block. */
    @Test
    void largePayloadFetchSizesAreSetOnTheFactoryThatIsActuallyUsed() {
        Map<String, Object> props = consumerProps();

        assertThat(props.get(ConsumerConfig.FETCH_MAX_BYTES_CONFIG))
                .as("fetch.max.bytes lived in the dead yaml block and therefore never applied")
                .isEqualTo(TWENTY_MIB);
        assertThat(props.get(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG))
                .as("max.partition.fetch.bytes lived in the dead yaml block too")
                .isEqualTo(TWENTY_MIB);
    }
}
