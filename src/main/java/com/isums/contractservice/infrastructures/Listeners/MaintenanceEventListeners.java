package com.isums.contractservice.infrastructures.Listeners;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.InspectionDoneNotifyEvent;
import com.isums.contractservice.domains.events.JobCompletedEvent;
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

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MaintenanceEventListeners {

    private final EContractRepository contractRepo;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final KafkaListenerHelper kafkaHelper;

    @KafkaListener(topics = "job.inspection.done", groupId = "contract-group")
    public void handleInspectionDone(ConsumerRecord<String, String> record, Acknowledgment ack) {

        String messageId = kafkaHelper.extractMessageId(record);
        try {
            if (idempotencyService.isDuplicate(messageId)) {
                ack.acknowledge();
                return;
            }

            JobCompletedEvent event = objectMapper.readValue(
                    record.value(), JobCompletedEvent.class);

            if (event.getContractId() == null) {
                log.warn("[Contract] InspectionDone missing contractId, skip");
                ack.acknowledge();
                return;
            }

            EContract contract = contractRepo.findById(event.getContractId())
                    .orElseThrow();

            contract.getStatus().validateTransition(EContractStatus.INSPECTION_DONE);
            contract.setStatus(EContractStatus.INSPECTION_DONE);
            contractRepo.save(contract);

            kafka.send("contract.inspection.done",
                    event.getContractId().toString(),
                    InspectionDoneNotifyEvent.builder()
                            .contractId(event.getContractId())
                            .inspectionId(event.getReferenceId())
                            .managerId(contract.getCreatedBy())
                            .deductionAmount(0L)
                            .messageId(UUID.randomUUID().toString())
                            .build());

            idempotencyService.markProcessed(messageId);
            ack.acknowledge();
            log.info("[Contract] INSPECTION_DONE contractId={}", event.getContractId());
        } catch (Exception e) {
            log.error("[Contract] handleInspectionDone failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
