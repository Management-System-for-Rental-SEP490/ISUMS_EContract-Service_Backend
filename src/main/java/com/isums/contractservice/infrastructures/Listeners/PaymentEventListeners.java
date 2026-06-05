package com.isums.contractservice.infrastructures.Listeners;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.ContractRelocationRequest;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RelocationFaultParty;
import com.isums.contractservice.domains.enums.RelocationRequestKind;
import com.isums.contractservice.domains.enums.RelocationRequestStatus;
import com.isums.contractservice.domains.events.*;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.ContractRelocationRequestRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.services.OutboxPublisher;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListeners {

    private final EContractRepository contractRepo;
    private final ContractRelocationRequestRepository relocationRepo;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final KafkaListenerHelper kafkaHelper;
    private final UserGrpcClient userGrpcClient;
    private final OutboxPublisher outboxPublisher;

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

            relocationRepo.findFirstByOldContractIdAndStatusInOrderByCreatedAtDesc(
                    event.getContractId(),
                    java.util.List.of(RelocationRequestStatus.REFUND_PENDING))
                    .ifPresent(relocation -> {
                        relocation.setStatus(RelocationRequestStatus.COMPLETED);
                        relocation.setDepositStatusSnapshot(DepositStatus.REFUNDED);
                        relocation.setCompletedAt(Instant.now());
                        relocationRepo.save(relocation);
                    });

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

            if (contract.getStatus() != EContractStatus.IN_PROGRESS) {
                ack.acknowledge();
                return;
            }

            if (contract.getTerminationRequestedAt() == null) {
                contract.setTerminationRequestedAt(Instant.now());
                contractRepo.save(contract);
            }

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

    @KafkaListener(topics = "deposit-paid-topic", groupId = "contract-group")
    public void handleDepositPaid(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String messageId = kafkaHelper.extractMessageId(record);
        try {
            if (idempotencyService.isDuplicate(messageId)) {
                ack.acknowledge();
                return;
            }

            DepositPaidEvent event = objectMapper.readValue(record.value(), DepositPaidEvent.class);

            contractRepo.findById(event.contractId()).ifPresent(contract -> {
                contract.setDepositStatus(DepositStatus.PAID);
                contractRepo.save(contract);
                log.info("[Contract] Deposit marked PAID contractId={} invoiceId={}",
                        event.contractId(), event.invoiceId());
            });

            idempotencyService.markProcessed(messageId);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[Contract] handleDepositPaid failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @KafkaListener(topics = "deposit-paid-enriched-topic", groupId = "contract-group")
    @Transactional
    public void handleNewContractActivated(
            ConsumerRecord<String, String> record, Acknowledgment ack) {
        String messageId = kafkaHelper.extractMessageId(record);
        try {
            if (idempotencyService.isDuplicate(messageId)) {
                ack.acknowledge();
                return;
            }

            DepositPaidEvent event = objectMapper.readValue(record.value(), DepositPaidEvent.class);
            UUID newContractId = event.contractId();
            if (newContractId == null) {
                ack.acknowledge();
                return;
            }

            EContract newContract = contractRepo.findById(newContractId).orElse(null);
            if (newContract == null || newContract.getRelocationSourceContractId() == null) {
                idempotencyService.markProcessed(messageId);
                ack.acknowledge();
                return;
            }

            UUID oldContractId = newContract.getRelocationSourceContractId();
            EContract oldContract = contractRepo.findById(oldContractId).orElse(null);
            if (oldContract == null
                    || oldContract.getStatus() != EContractStatus.PENDING_REPLACEMENT_HANDOVER) {
                idempotencyService.markProcessed(messageId);
                ack.acknowledge();
                return;
            }

            ContractRelocationRequest relocation = relocationRepo
                    .findByNewContractId(newContractId).orElse(null);
            if (relocation != null
                    && relocation.getRequestKind() == RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE) {
                idempotencyService.markProcessed(messageId);
                ack.acknowledge();
                log.info("[Contract] Active-lease relocation waits for handover oldContractId={} newContractId={}",
                        oldContractId, newContractId);
                return;
            }

            oldContract.getStatus().validateTransition(EContractStatus.REPLACED_AFTER_DEPOSIT);
            oldContract.setStatus(EContractStatus.REPLACED_AFTER_DEPOSIT);
            contractRepo.save(oldContract);

            if (relocation != null && relocation.getStatus() != RelocationRequestStatus.COMPLETED) {
                relocation.setStatus(RelocationRequestStatus.COMPLETED);
                relocation.setCompletedAt(Instant.now());
                relocationRepo.save(relocation);
            }

            publishContractReplaced(oldContract, newContract, relocation);

            idempotencyService.markProcessed(messageId);
            ack.acknowledge();
            log.info("[Contract] Auto-completed active-lease relocation oldContractId={} newContractId={}",
                    oldContractId, newContractId);
        } catch (Exception e) {
            log.error("[Contract] handleNewContractActivated failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private void publishContractReplaced(EContract oldContract,
                                         EContract newContract,
                                         ContractRelocationRequest relocation) {
        boolean landlordFault = relocation != null
                && relocation.getFaultParty() == RelocationFaultParty.LANDLORD;
        UUID newHouseId = newContract.getHouseId();
        if (relocation != null) {
            newHouseId = relocation.getApprovedHouseId() != null
                    ? relocation.getApprovedHouseId()
                    : (relocation.getRequestedHouseId() != null
                            ? relocation.getRequestedHouseId() : newHouseId);
        }
        Long transferred = relocation != null && relocation.getTransferredDepositAmount() != null
                ? relocation.getTransferredDepositAmount() : 0L;
        String depositHandling = relocation != null && relocation.getDepositHandling() != null
                ? relocation.getDepositHandling().name() : null;
        String reason = landlordFault
                ? (relocation.getStaffReportReason() != null
                        && !relocation.getStaffReportReason().isBlank()
                        ? relocation.getStaffReportReason()
                        : "Landlord-fault relocation")
                : "Tenant active-lease upgrade auto-completed on new deposit";
        String eventMessageId = UUID.randomUUID().toString();
        ContractReplacedEvent event = ContractReplacedEvent.builder()
                .messageId(eventMessageId)
                .oldContractId(oldContract.getId())
                .newContractId(newContract.getId())
                .oldHouseId(oldContract.getHouseId())
                .newHouseId(newHouseId)
                .tenantId(oldContract.getUserId())
                .keepHouseUnavailable(landlordFault)
                .depositHandling(depositHandling)
                .transferredDepositAmount(transferred)
                .reason(reason)
                .replacedAt(Instant.now())
                .newHandoverDate(newContract.getHandoverDate())
                .build();
        outboxPublisher.enqueue(
                "contract.replaced",
                oldContract.getId().toString(),
                event,
                eventMessageId);
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
