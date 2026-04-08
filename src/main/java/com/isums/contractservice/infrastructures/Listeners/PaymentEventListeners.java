package com.isums.contractservice.infrastructures.Listeners;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.ContractTerminatedEvent;
import com.isums.contractservice.domains.events.DepositRefundPaidEvent;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import common.kafkas.IdempotencyService;
import common.kafkas.KafkaListenerHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListeners {

    private final EContractRepository contractRepo;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final KafkaListenerHelper kafkaHelper;

    @KafkaListener(topics = "deposit-refund-paid-topic", groupId = "contract-group")
    public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String messageId = kafkaHelper.extractMessageId(record);
        try {
            if (idempotencyService.isDuplicate(messageId)) {
                ack.acknowledge();
                return;
            }

            DepositRefundPaidEvent event = objectMapper.readValue(record.value(), DepositRefundPaidEvent.class);

            EContract contract = contractRepo.findById(event.getContractId())
                    .orElseThrow(() -> new RuntimeException(
                            "Contract not found: " + event.getContractId()));

            contract.getStatus().validateTransition(EContractStatus.TERMINATED);
            contract.setStatus(EContractStatus.TERMINATED);
            contract.setTerminatedAt(Instant.now());
            contractRepo.save(contract);

            kafka.send("contract.terminated",
                    event.getContractId().toString(),
                    ContractTerminatedEvent.builder()
                            .contractId(event.getContractId())
                            .houseId(event.getHouseId())
                            .tenantId(event.getTenantId())
                            .messageId(UUID.randomUUID().toString())
                            .build());

            idempotencyService.markProcessed(messageId);
            ack.acknowledge();
            log.info("[Contract] TERMINATED contractId={}", event.getContractId());

        } catch (Exception e) {
            log.error("[Contract] DepositRefundPaidConsumer failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
