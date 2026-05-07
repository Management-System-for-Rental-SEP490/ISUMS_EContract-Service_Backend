package com.isums.contractservice.schedulers;

import com.isums.contractservice.domains.entities.ContractRelocationRequest;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.RelocationFaultParty;
import com.isums.contractservice.domains.enums.RelocationRequestStatus;
import com.isums.contractservice.domains.events.ContractReplacedEvent;
import com.isums.contractservice.infrastructures.repositories.ContractRelocationRequestRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.services.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RelocationReconciliationScheduler {

    private final ContractRelocationRequestRepository relocationRepo;
    private final EContractRepository contractRepo;
    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelay = 600_000L, initialDelay = 60_000L)
    public void reconcileRecentlyCompleted() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<ContractRelocationRequest> recent = relocationRepo
                .findByStatusAndCompletedAtAfter(RelocationRequestStatus.COMPLETED, cutoff);

        if (recent.isEmpty()) {
            return;
        }

        int republished = 0;
        for (ContractRelocationRequest reloc : recent) {
            try {
                EContract old = contractRepo.findById(reloc.getOldContractId()).orElse(null);
                if (old == null) {
                    log.warn("[RelocReconcile] Old contract not found relocId={}", reloc.getId());
                    continue;
                }

                boolean landlordFault = reloc.getFaultParty() == RelocationFaultParty.LANDLORD;
                UUID newHouseId = reloc.getApprovedHouseId() != null
                        ? reloc.getApprovedHouseId()
                        : reloc.getRequestedHouseId();

                String messageId = UUID.randomUUID().toString();
                ContractReplacedEvent event = ContractReplacedEvent.builder()
                        .messageId(messageId)
                        .oldContractId(old.getId())
                        .newContractId(null)
                        .oldHouseId(old.getHouseId())
                        .newHouseId(newHouseId)
                        .tenantId(old.getUserId())
                        .keepHouseUnavailable(landlordFault)
                        .depositHandling(reloc.getDepositHandling() != null
                                ? reloc.getDepositHandling().name() : null)
                        .transferredDepositAmount(reloc.getTransferredDepositAmount() != null
                                ? reloc.getTransferredDepositAmount() : 0L)
                        .reason("reconciliation-replay")
                        .replacedAt(Instant.now())
                        .build();

                outboxPublisher.enqueue(
                        "contract.replaced",
                        old.getId().toString(),
                        event,
                        messageId);
                republished++;

                log.info("[RelocReconcile] Re-enqueued contract.replaced relocId={} oldContractId={} oldHouseId={} tenantId={}",
                        reloc.getId(), old.getId(), old.getHouseId(), old.getUserId());
            } catch (Exception e) {
                log.error("[RelocReconcile] Failed to reconcile relocId={}: {}",
                        reloc.getId(), e.getMessage(), e);
            }
        }

        log.info("[RelocReconcile] Done relocations={} republished={}", recent.size(), republished);
    }
}
