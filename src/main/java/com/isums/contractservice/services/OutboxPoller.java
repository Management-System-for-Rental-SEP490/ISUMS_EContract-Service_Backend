package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.OutboxEvent;
import com.isums.contractservice.infrastructures.repositories.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OutboxPoller {

    private static final int BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS_BEFORE_GIVE_UP = 10;
    private static final int LAST_ERROR_MAX_LEN = 4000;
    private static final long SEND_TIMEOUT_SECONDS = 8L;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(15);

    private final OutboxEventRepository repo;
    private final KafkaTemplate<String, Object> kafka;
    private final TransactionTemplate txTemplate;

    public OutboxPoller(OutboxEventRepository repo,
                        KafkaTemplate<String, Object> kafka,
                        PlatformTransactionManager tm) {
        this.repo = repo;
        this.kafka = kafka;
        this.txTemplate = new TransactionTemplate(tm);
    }

    @Scheduled(fixedDelay = 2_000)
    public void tick() {
        List<UUID> claimed;
        try {
            claimed = claimBatch();
        } catch (Exception ex) {
            log.warn("[OutboxPoller] claimBatch failed — {}", ex.toString());
            return;
        }
        if (claimed.isEmpty()) return;
        log.debug("[OutboxPoller] tick — {} events claimed", claimed.size());
        for (UUID id : claimed) {
            try {
                publishOne(id);
            } catch (Exception ex) {
                log.warn("[OutboxPoller] publishOne crash id={} — {}", id, ex.toString());
            }
        }
    }

    private List<UUID> claimBatch() {
        List<UUID> ids = txTemplate.execute(status -> {
            Instant retryAfter = Instant.now().minus(RETRY_BACKOFF);
            List<OutboxEvent> batch = repo.lockUnsentBatch(
                    MAX_ATTEMPTS_BEFORE_GIVE_UP, retryAfter,
                    PageRequest.of(0, BATCH_SIZE));
            Instant now = Instant.now();
            List<UUID> out = new ArrayList<>(batch.size());
            for (OutboxEvent e : batch) {
                int prev = e.getAttempts() == null ? 0 : e.getAttempts();
                e.setAttempts(prev + 1);
                e.setLastAttemptAt(now);
                out.add(e.getId());
            }
            return out;
        });
        return ids != null ? ids : List.of();
    }

    private void publishOne(UUID id) {
        OutboxEvent event = txTemplate.execute(s -> repo.findById(id).orElse(null));
        if (event == null || event.getSentAt() != null) return;

        ProducerRecord<String, Object> record = buildRecord(event);
        try {
            kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            txTemplate.executeWithoutResult(s -> {
                OutboxEvent fresh = repo.findById(id).orElse(null);
                if (fresh == null) return;
                fresh.setSentAt(Instant.now());
                fresh.setLastError(null);
            });
            log.info("[Outbox] sent topic={} messageId={} attempts={}",
                    event.getTopic(), event.getMessageId(), event.getAttempts());
        } catch (Exception ex) {
            String msg = ex.getClass().getSimpleName() + ": "
                    + (ex.getMessage() == null ? "null" : ex.getMessage());
            String trimmed = msg.length() > LAST_ERROR_MAX_LEN
                    ? msg.substring(0, LAST_ERROR_MAX_LEN) : msg;
            try {
                txTemplate.executeWithoutResult(s -> {
                    OutboxEvent fresh = repo.findById(id).orElse(null);
                    if (fresh == null) return;
                    fresh.setLastError(trimmed);
                });
            } catch (Exception ignore) {
            }
            log.warn("[Outbox] publish failed topic={} messageId={} attempt={} — {}",
                    event.getTopic(), event.getMessageId(), event.getAttempts(), trimmed);
        }
    }

    private ProducerRecord<String, Object> buildRecord(OutboxEvent event) {
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
        return new ProducerRecord<>(
                event.getTopic(),
                null,
                event.getPartitionKey(),
                event.getPayload(),
                headers);
    }
}
