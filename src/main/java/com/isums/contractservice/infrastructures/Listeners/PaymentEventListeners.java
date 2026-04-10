package com.isums.contractservice.infrastructures.Listeners;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.*;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.userservice.grpc.UserResponse;
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
import java.util.Map;
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
    private final UserGrpcClient userGrpcClient;

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

    @KafkaListener(topics = "payment.termination-requested", groupId = "contract-group")
    public void handleTerminationRequested(
            ConsumerRecord<String, String> record, Acknowledgment ack) {

        String messageId = kafkaHelper.extractMessageId(record);
        try {
            if (idempotencyService.isDuplicate(messageId)) {
                ack.acknowledge();
                return;
            }

            TerminationRequestedEvent event = objectMapper.readValue(
                    record.value(), TerminationRequestedEvent.class);

            EContract contract = contractRepo.findById(event.getContractId())
                    .orElseThrow();

            if (contract.getStatus() != EContractStatus.IN_PROGRESS && contract.getStatus() != EContractStatus.COMPLETED) {
                ack.acknowledge();
                return;
            }

            // Notify manager qua email
            UserResponse manager = userGrpcClient
                    .getUserById(contract.getCreatedBy().toString());

            kafka.send("notification-email",
                    SendEmailEvent.builder()
                            .to(manager.getEmail())
                            .templateCode("overdue_termination_notice")
                            .messageId(UUID.randomUUID().toString())
                            .params(Map.of(
                                    "managerName", manager.getName(),
                                    "contractId",  contract.getId().toString()
                                            .substring(0, 8).toUpperCase(),
                                    "tenantName",  contract.getTenantName(),
                                    "daysLate",    "30"
                            ))
                            .build());

            // Notify manager qua web notification
            kafka.send("contract.termination-overdue-requested",
                    event.getContractId().toString(),
                    OverdueTerminationRequestedEvent.builder()
                            .contractId(event.getContractId())
                            .houseId(event.getHouseId())
                            .managerId(contract.getCreatedBy())
                            .tenantName(contract.getTenantName())
                            .messageId(UUID.randomUUID().toString())
                            .build());

            idempotencyService.markProcessed(messageId);
            ack.acknowledge();
            log.info("[Contract] Termination requested contractId={}", event.getContractId());

        } catch (Exception e) {
            log.error("[Contract] handleTerminationRequested failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "payment.power-cut-requested", groupId = "contract-group")
    public void handlePowerCutRequest(
            ConsumerRecord<String, String> record, Acknowledgment ack) {

        String messageId = kafkaHelper.extractMessageId(record);
        try {
            if (idempotencyService.isDuplicate(messageId)) {
                ack.acknowledge();
                return;
            }

            PowerCutRequestEvent event = objectMapper.readValue(
                    record.value(), PowerCutRequestEvent.class);

            EContract contract = contractRepo.findById(event.getContractId())
                    .orElseThrow();

            kafka.send("contract.power-cut-review-requested",
                    event.getContractId().toString(),
                    PowerCutReviewRequestedEvent.builder()
                            .contractId(event.getContractId())
                            .houseId(event.getHouseId())
                            .managerId(contract.getCreatedBy())
                            .tenantName(contract.getTenantName())
                            .daysLate(event.getDaysLate())
                            .totalAmount(event.getTotalAmount())
                            .messageId(UUID.randomUUID().toString())
                            .build());

            idempotencyService.markProcessed(messageId);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[Contract] handlePowerCutRequest failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
