package com.isums.contractservice.schedulers;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContractExpiryScheduler {

    private final EContractRepository contractRepo;
    private final ContractTerminationService contractTerminationService;

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Ho_Chi_Minh")
    public void processExpiredContracts() {
        Instant now = Instant.now();

        List<EContract> expired = contractRepo.findByStatusAndEndAtBefore(EContractStatus.IN_PROGRESS, now);

        log.info("[ContractExpiry] Found {} expired contracts", expired.size());

        for (EContract contract : expired) {
            try {
                contractTerminationService.handleExpiredContract(contract);
            } catch (Exception e) {
                log.error("[ContractExpiry] Failed contractId={}: {}",
                        contract.getId(), e.getMessage(), e);
            }
        }
    }
}
