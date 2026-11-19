package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Rebuilds a projection by reading {@link AuditTopics#AGENT_ACTIONS} from its beginning with a
 * fresh, disposable consumer group (never the live projection's own {@code audit-projection}
 * group, so a replay never disturbs the live consumer's committed offsets) and folding every
 * record the same way {@link AuditProjectionRoute} does. The fold is a pure function of the log:
 * same log in, same projection out, every time, which is the whole of this chapter's thesis.
 *
 * <p>{@link #run()} rebuilds the scratch REPLAY table and reports whether it matches the live
 * one; {@link #rebuildLive()} is the recovery path demonstrated in {@code verify.sh}: if the live
 * table was ever corrupted by a direct mutation that bypassed the log (see
 * {@code AuditResource#mutate}), the log itself was never touched, so rebuilding LIVE from it
 * erases the corruption without losing anything the log actually recorded.
 */
@ApplicationScoped
public class ReplayService {

    /** The result of folding the log into REPLAY and comparing it against LIVE. */
    public record Result(long recordsReplayed, boolean matchesLive, long liveRows, long replayRows, long millis) {
    }

    /** The result of rebuilding LIVE from the log (the corruption-recovery path). */
    public record RebuildResult(long recordsFolded, long liveRows, long millis) {
    }

    private static final Logger LOG = Logger.getLogger(ReplayService.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_BUDGET = Duration.ofSeconds(30);

    @ConfigProperty(name = "audit.kafka.bootstrap-servers")
    String bootstrapServers;

    @Inject
    AuditProjectionStore store;

    public Result run() {
        long started = System.nanoTime();
        store.truncate(AuditProjectionStore.ProjectionTable.REPLAY);
        long replayed = foldFromBeginning(AuditProjectionStore.ProjectionTable.REPLAY);
        boolean matches = store.snapshot(AuditProjectionStore.ProjectionTable.LIVE)
                .equals(store.snapshot(AuditProjectionStore.ProjectionTable.REPLAY));
        long millis = (System.nanoTime() - started) / 1_000_000;
        LOG.infof("replay folded %d records from the log; matchesLive=%s", replayed, matches);
        return new Result(replayed, matches, store.count(AuditProjectionStore.ProjectionTable.LIVE),
                store.count(AuditProjectionStore.ProjectionTable.REPLAY), millis);
    }

    public RebuildResult rebuildLive() {
        long started = System.nanoTime();
        store.truncate(AuditProjectionStore.ProjectionTable.LIVE);
        long folded = foldFromBeginning(AuditProjectionStore.ProjectionTable.LIVE);
        long millis = (System.nanoTime() - started) / 1_000_000;
        LOG.infof("live projection rebuilt from the untouched log: %d records folded", folded);
        return new RebuildResult(folded, store.count(AuditProjectionStore.ProjectionTable.LIVE), millis);
    }

    /** Reads every committed record on the topic, from the beginning, into {@code target}. */
    private long foldFromBeginning(AuditProjectionStore.ProjectionTable target) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-replay-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        long folded = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(AuditTopics.AGENT_ACTIONS);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return 0;
            }
            List<TopicPartition> partitions = partitionInfos.stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            long deadline = System.nanoTime() + READ_BUDGET.toNanos();
            while (!reachedEnd(consumer, partitions, endOffsets) && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : records) {
                    AuditRecord parsed = AuditJson.MAPPER.readValue(record.value(), AuditRecord.class);
                    store.append(target, parsed);
                    folded++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("replay could not parse a committed audit record", e);
        }
        return folded;
    }

    private static boolean reachedEnd(KafkaConsumer<String, String> consumer, List<TopicPartition> partitions,
            Map<TopicPartition, Long> endOffsets) {
        for (TopicPartition partition : partitions) {
            if (consumer.position(partition) < endOffsets.get(partition)) {
                return false;
            }
        }
        return true;
    }
}
