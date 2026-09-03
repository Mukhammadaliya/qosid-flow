package uz.greenwhite.gateway.concurrency;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uz.greenwhite.gateway.config.ConcurrencyProperties;
import uz.greenwhite.gateway.config.KafkaProperties;

import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The lag monitor runs on a fixed 10 s delay and talks to Kafka on every tick, so whatever it
 * logs on failure it logs 8 640 times a day. On the demo box a downed broker turned that into
 * 4 257 ERROR entries reading "Error monitoring consumer lag: null" — each with a full stack
 * trace, each naming neither the fault nor its type, in a log file that grew to 4.7 GB.
 *
 * <p>What this pins down: a repeating unreachable-broker fault must stay legible (name the
 * exception type) and stay bounded (not one entry per tick), and recovery must be visible.
 */
class ConcurrencyMonitorLoggingTest {

    private AdminClient adminClient;
    private ConcurrencyMonitorService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;
    private Level originalLevel;

    /** Flips to make the mocked AdminClient succeed or fail. */
    private boolean brokerReachable;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        adminClient = mock(AdminClient.class);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> future = mock(KafkaFuture.class);
        ListConsumerGroupOffsetsResult offsets = mock(ListConsumerGroupOffsetsResult.class);
        when(offsets.partitionsToOffsetAndMetadata()).thenReturn(future);
        when(adminClient.listConsumerGroupOffsets(anyString())).thenReturn(offsets);
        // A broker that is merely unreachable produces a TimeoutException with NO message —
        // the exact shape that used to be logged as the word "null".
        when(future.get(anyLong(), any())).thenAnswer(inv -> {
            if (!brokerReachable) throw new TimeoutException();
            return Map.of();   // reachable but nothing committed yet: the quiet, healthy path
        });
        brokerReachable = false;

        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setGroupId("test-group");
        service = new ConcurrencyMonitorService(adminClient, mock(DynamicConcurrencyManager.class),
                new ConcurrencyProperties(), kafkaProperties);

        serviceLogger = (Logger) LoggerFactory.getLogger(ConcurrencyMonitorService.class);
        // Pin the threshold instead of inheriting it. The app's application.yml runs
        // uz.greenwhite.gateway at DEBUG, and once any Spring context in the suite has configured
        // logback, the DEBUG detail line lands in this appender too — the counts below then measure
        // the ambient level rather than the back-off. This test is about what an operator sees at
        // INFO and above; the DEBUG line is deliberately excluded.
        originalLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        serviceLogger.setLevel(originalLevel);
    }

    @Test
    void namesTheExceptionTypeInsteadOfLoggingTheWordNull() {
        service.monitorAndScale();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getFormattedMessage()).contains("TimeoutException");
        assertThat(event.getFormattedMessage()).doesNotContain("null");
    }

    @Test
    void aPersistentOutageDoesNotProduceOneEntryPerTick() {
        for (int i = 0; i < 50; i++) {
            service.monitorAndScale();
        }
        // 50 ticks of the same unreachable broker is one fact, not fifty.
        assertThat(appender.list).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void omitsTheStackTraceForARepeatingUnreachableBroker() {
        service.monitorAndScale();

        // The stack of a scheduled poll is identical every time and says nothing the message
        // does not. Kept for DEBUG, not for 8 640 entries a day.
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void recoveryIsReportedSoTheOutageHasAVisibleEnd() {
        service.monitorAndScale();
        appender.list.clear();

        brokerReachable = true;
        service.monitorAndScale();

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage().toLowerCase()).contains("recover");
        });
    }
}
