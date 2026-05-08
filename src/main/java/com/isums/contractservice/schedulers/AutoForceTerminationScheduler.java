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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoForceTerminationScheduler {

    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    private static final long GRACE_DAYS = 7L;

    private final EContractRepository contractRepo;
    private final ContractTerminationService terminationService;

    @Scheduled(cron = "0 30 5 * * *", zone = "Asia/Ho_Chi_Minh")
    public void autoForceTerminate() {
        Instant cutoff = Instant.now().minus(GRACE_DAYS, ChronoUnit.DAYS);
        List<EContract> stale = contractRepo.findByStatusAndTerminationRequestedAtBefore(
                EContractStatus.IN_PROGRESS, cutoff);

        if (stale.isEmpty()) return;

        log.info("[AutoForceTerm] Found {} contracts stuck > {} days after termination request",
                stale.size(), GRACE_DAYS);

        for (EContract c : stale) {
            try {
                terminationService.confirmTerminationOverdue(c.getId(), SYSTEM_ACTOR);
                log.info("[AutoForceTerm] Auto-terminated contractId={} requestedAt={}",
                        c.getId(), c.getTerminationRequestedAt());
            } catch (Exception e) {
                log.error("[AutoForceTerm] Failed contractId={}: {}",
                        c.getId(), e.getMessage(), e);
            }
        }
    }
}
