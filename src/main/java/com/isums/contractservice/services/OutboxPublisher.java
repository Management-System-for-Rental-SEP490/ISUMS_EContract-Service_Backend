package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.OutboxEvent;
import com.isums.contractservice.infrastructures.repositories.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Enqueues events into the transactional outbox. Call this inside a @Transactional
 * method that also writes business state — both commit or both roll back, so
 * either the state change happens AND the event ships, or neither does.
 *
 * Downstream: {@link OutboxPoller} polls, publishes to Kafka, marks sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository repo;
    private final ObjectMapper mapper;

    /**
     * Enqueue an event. MUST be called inside an existing transaction — otherwise
     * the point of the outbox is defeated.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String topic, String partitionKey, Object payload, String messageId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = mapper.convertValue(payload, Map.class);

        OutboxEvent event = OutboxEvent.builder()
                .topic(topic)
                .partitionKey(partitionKey)
                .payload(payloadMap)
                .messageId(messageId != null ? messageId : UUID.randomUUID().toString())
                .createdAt(Instant.now())
                .attempts(0)
                .build();
        OutboxEvent saved = repo.save(event);
        log.info("[Outbox] enqueued topic={} key={} messageId={} outboxId={}",
                topic, partitionKey, saved.getMessageId(), saved.getId());
        return saved.getId();
    }

    public UUID enqueue(String topic, String partitionKey, Object payload) {
        return enqueue(topic, partitionKey, payload, UUID.randomUUID().toString());
    }
}
