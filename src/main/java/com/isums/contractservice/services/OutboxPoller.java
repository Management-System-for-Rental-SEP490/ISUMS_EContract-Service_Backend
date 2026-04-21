package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.OutboxEvent;
import com.isums.contractservice.infrastructures.repositories.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Polls the outbox for unsent events and publishes to Kafka. Marks {@code sent_at}
 * on success; records error + attempts on failure so {@link KafkaErrorHandler}-
 * style DLT or operator visibility is possible via DB query.
 *
 * Batch size 50 per tick balances throughput vs lock contention. Fixed 2s delay
 * gives good latency for the confirm-email flow without hammering Postgres.
 *
 * Ordering: Postgres ORDER BY created_at gives FIFO per key; Kafka's partition
 * assignment (using partitionKey as the record key) preserves ordering within
 * a partition-key group. Good enough for contract lifecycle where cross-contract
 * ordering doesn't matter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS_BEFORE_GIVE_UP = 10;
    private static final long LAST_ERROR_MAX_LEN = 4000;

    private final OutboxEventRepository repo;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper mapper;

    @Scheduled(fixedDelay = 2_000)
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch = repo.lockUnsentBatch(
                MAX_ATTEMPTS_BEFORE_GIVE_UP, PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) return;

        log.debug("[OutboxPoller] tick — {} unsent events locked", batch.size());
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastAttemptAt(Instant.now());

        try {
            List<Header> headers = new ArrayList<>();
            headers.add(new RecordHeader("messageId",
                    event.getMessageId().getBytes(StandardCharsets.UTF_8)));
            if (event.getHeaders() != null) {
                for (Map.Entry<String, String> h : event.getHeaders().entrySet()) {
                    if (h.getValue() == null) continue;
                    headers.add(new RecordHeader(h.getKey(),
                            h.getValue().getBytes(StandardCharsets.UTF_8)));
                }
            }

            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    event.getTopic(),
                    null,
                    event.getPartitionKey(),
                    mapper.writeValueAsString(event.getPayload()),
                    headers);

            kafka.send(record).get();   // block until broker ack (at-least-once)
            event.setSentAt(Instant.now());
            event.setLastError(null);
            repo.save(event);
            log.info("[Outbox] sent topic={} messageId={} attempts={}",
                    event.getTopic(), event.getMessageId(), event.getAttempts());

        } catch (Exception ex) {
            String msg = ex.getClass().getSimpleName() + ": "
                    + (ex.getMessage() == null ? "null" : ex.getMessage());
            event.setLastError(msg.length() > LAST_ERROR_MAX_LEN
                    ? msg.substring(0, (int) LAST_ERROR_MAX_LEN) : msg);
            repo.save(event);
            log.warn("[Outbox] publish failed topic={} messageId={} attempt={} — {}",
                    event.getTopic(), event.getMessageId(), event.getAttempts(), msg);
        }
    }
}
