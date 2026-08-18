package uz.greenwhite.gateway.concurrency;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uz.greenwhite.gateway.config.ConcurrencyProperties;
import uz.greenwhite.gateway.config.KafkaProperties;
import uz.greenwhite.gateway.util.ErrorText;
import uz.greenwhite.gateway.util.RepeatingFailureLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConcurrencyMonitorService {

    private final AdminClient adminClient;
    private final DynamicConcurrencyManager concurrencyManager;
    private final ConcurrencyProperties properties;
    private final KafkaProperties kafkaProperties;

    private final Map<String, Long> lagMap = new ConcurrentHashMap<>();

    /** Reporting policy for the repeating-failure case; see SAFETY-RULES 42. */
    private final RepeatingFailureLog monitorFailures = new RepeatingFailureLog(100);

    @PostConstruct
    public void init() {
        log.info("ConcurrencyMonitor started: group={}, interval={}ms, " +
                        "oracle=[{}, {}], api=[{}, {}]",
                kafkaProperties.getGroupId(),
                properties.getMonitorIntervalMs(),
                kafkaProperties.getTopics().getOracleRequest(),
                kafkaProperties.getTopics().getOracleResponse(),
                kafkaProperties.getTopics().getApiRequest(),
                kafkaProperties.getTopics().getApiResponse());
    }

    @Scheduled(fixedDelayString = "${gateway.concurrency.monitor-interval-ms:10000}")
    public void monitorAndScale() {
        try {
            Map<TopicPartition, OffsetAndMetadata> allCommittedOffsets = getCommittedOffsets();
            // The admin call came back — that alone is the recovery signal, so it is noted here
            // rather than after the scaling work below, which returns early on an empty group.
            noteMonitorSuccess();

            if (allCommittedOffsets == null || allCommittedOffsets.isEmpty()) {
                log.debug("No committed offsets found for group: {}", kafkaProperties.getGroupId());
                return;
            }

            // Oracle request consumer
            monitorTopic(kafkaProperties.getTopics().getOracleRequest(),
                    "oracleRequestConsumer", allCommittedOffsets);

            // Oracle response consumer
            monitorTopic(kafkaProperties.getTopics().getOracleResponse(),
                    "oracleResponseConsumer", allCommittedOffsets);

            // API request consumer
            monitorTopic(kafkaProperties.getTopics().getApiRequest(),
                    "apiRequestConsumer", allCommittedOffsets);

            // API response consumer
            monitorTopic(kafkaProperties.getTopics().getApiResponse(),
                    "apiResponseConsumer", allCommittedOffsets);

        } catch (Exception e) {
            noteMonitorFailure(e);
        }
    }

    /**
     * This method runs every 10 s, so anything it logs unconditionally on failure it logs
     * ~8 640 times a day. A downed broker used to produce exactly that: 4 257 ERROR entries in
     * one day reading "Error monitoring consumer lag: null" — {@code TimeoutException} carries
     * no message — each with an identical stack trace. That is the shape of the 4.7 GB log file
     * found on the demo box, and the same shape that filled a 57 GB volume in May.
     *
     * <p>So: name the exception type, log the first occurrence and then only every hundredth,
     * and leave the stack trace to DEBUG. An unreachable broker is not silent even so — the
     * actuator health indicator reports lag, enqueue failures are logged where they happen, and
     * DLQ alerts still fire.
     */
    private void noteMonitorFailure(Exception e) {
        if (monitorFailures.noteFailure()) {
            log.warn("Consumer lag monitoring failed ({} consecutive): {}",
                    monitorFailures.consecutive(), ErrorText.brief(e));
        }
        log.debug("Consumer lag monitoring failure detail", e);
    }

    /** Closes the outage in the log, so a reader can tell "still broken" from "was broken". */
    private void noteMonitorSuccess() {
        int ended = monitorFailures.noteSuccess();
        if (ended > 0) {
            log.info("Consumer lag monitoring recovered after {} consecutive failure(s)", ended);
        }
    }

    private void monitorTopic(String topic, String listenerId,
                              Map<TopicPartition, OffsetAndMetadata> allCommittedOffsets) throws Exception {
        long lag = calculateLagForTopic(topic, allCommittedOffsets);
        lagMap.put(topic, lag);
        log.debug("Consumer lag [{}]: {} messages", topic, lag);
        concurrencyManager.adjustConcurrency(listenerId, lag);
    }

    /**
     * Get committed offsets for the consumer group.
     */
    private Map<TopicPartition, OffsetAndMetadata> getCommittedOffsets() throws Exception {
        ListConsumerGroupOffsetsResult result =
                adminClient.listConsumerGroupOffsets(kafkaProperties.getGroupId());
        return result.partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
    }

    /**
     * Calculate total consumer lag for a specific topic.
     * Lag = sum of (end offset - committed offset) for each partition.
     */
    private long calculateLagForTopic(String topicName,
                                      Map<TopicPartition, OffsetAndMetadata> allCommittedOffsets) throws Exception {

        // 1. Filter committed offsets for this specific topic
        Map<TopicPartition, OffsetAndMetadata> topicOffsets = allCommittedOffsets.entrySet().stream()
                .filter(e -> e.getKey().topic().equals(topicName))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (topicOffsets.isEmpty()) {
            return 0;
        }

        // 2. Get end (latest) offsets for each partition
        Map<TopicPartition, OffsetSpec> offsetSpecMap = topicOffsets.keySet().stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                adminClient.listOffsets(offsetSpecMap).all().get(10, TimeUnit.SECONDS);

        // 3. Calculate total lag across all partitions
        long totalLag = 0;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : topicOffsets.entrySet()) {
            TopicPartition tp = entry.getKey();
            long committed = entry.getValue().offset();
            long end = endOffsets.containsKey(tp) ? endOffsets.get(tp).offset() : committed;
            totalLag += Math.max(0, end - committed);
        }

        return totalLag;
    }

    /**
     * Get the last known lag for a topic.
     * Used by external classes (metrics, health indicators).
     */
    public long getLastKnownLag(String topicName) {
        return lagMap.getOrDefault(topicName, 0L);
    }
}