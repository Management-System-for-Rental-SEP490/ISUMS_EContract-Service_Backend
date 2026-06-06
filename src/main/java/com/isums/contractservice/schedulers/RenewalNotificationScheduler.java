package com.isums.contractservice.schedulers;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.RenewalNotificationLog;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.domains.events.RenewalReminderEvent;
import com.isums.contractservice.domains.events.RenewalWindowOpenEvent;
import com.isums.contractservice.domains.events.SendEmailEvent;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalNotificationLogRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RenewalNotificationScheduler {

    private final EContractRepository contractRepo;
    private final RenewalNotificationLogRepository logRepo;
    private final RenewalRequestRepository renewalRequestRepo;
    private final UserGrpcClient userGrpcClient;
    private final KafkaTemplate<String, Object> kafka;

    private static final List<Integer> MILESTONES = List.of(60, 30, 14, 7, 3, 1, 0);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(VN);

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Ho_Chi_Minh")
    public void processRenewalNotifications() {
        LocalDate today = LocalDate.now(VN);

        Instant from = today.atStartOfDay(VN).toInstant();
        Instant to = today.plusDays(60).atStartOfDay(VN).toInstant();

        List<EContract> contracts = contractRepo.findByStatusInAndEndAtBetween(
                List.of(EContractStatus.IN_PROGRESS, EContractStatus.COMPLETED),
                from, to
        );

        log.info("[RenewalScheduler] Checking {} contracts", contracts.size());

        for (EContract contract : contracts) {
            try {
                processContract(contract, today);
            } catch (Exception e) {
                log.error("[RenewalScheduler] Failed contractId={}: {}", contract.getId(), e.getMessage());
            }
        }
    }

    public void simulateContractMilestone(EContract contract, LocalDate effectiveDate, UUID sessionId) {
        processContract(contract, effectiveDate, "DEMO_" + sessionId + "_");
    }

    private void processContract(EContract contract, LocalDate today) {
        processContract(contract, today, "");
    }

    private void processContract(EContract contract, LocalDate today, String milestonePrefix) {
        LocalDate endDate = contract.getEndAt()
                .atZone(VN).toLocalDate();

        long daysUntilEnd = ChronoUnit.DAYS.between(today, endDate);

        if (!MILESTONES.contains((int) daysUntilEnd)) return;

        boolean alreadyRequested = renewalRequestRepo.existsByContractIdAndStatusNotIn(contract.getId(),
                List.of(RenewalRequestStatus.DECLINED_BY_MANAGER, RenewalRequestStatus.CANCELLED_BY_TENANT)
        );
        if (alreadyRequested) return;

        String milestoneKey = milestonePrefix + contract.getId() + "_D_" + daysUntilEnd;
        if (logRepo.existsByMilestoneKey(milestoneKey)) return;

        kafka.send("contract.renewal.reminder",
                contract.getId().toString(),
                RenewalReminderEvent.builder()
                        .contractId(contract.getId())
                        .tenantId(contract.getUserId())
                        .daysRemaining((int) daysUntilEnd)
                        .endDate(contract.getEndAt())
                        .messageId(UUID.randomUUID().toString())
                        .build());

        sendRenewalReminderEmail(contract, (int) daysUntilEnd);

        if (daysUntilEnd == 30 || daysUntilEnd == 0) {
            kafka.send("contract.renewal-window.open",
                    contract.getHouseId().toString(),
                    RenewalWindowOpenEvent.builder()
                            .contractId(contract.getId())
                            .houseId(contract.getHouseId())
                            .messageId(UUID.randomUUID().toString())
                            .build());
        }

        logRepo.save(RenewalNotificationLog.builder()
                .milestoneKey(milestoneKey)
                .contractId(contract.getId())
                .daysRemaining((int) daysUntilEnd)
                .build());

        log.info("[RenewalScheduler] Notified contractId={} daysRemaining={}",
                contract.getId(), daysUntilEnd);
    }

    private void sendRenewalReminderEmail(EContract contract, int daysUntilEnd) {
        String tenantEmail = contract.getTenantEmail();
        try {
            UserResponse tenant = userGrpcClient.getUserById(contract.getUserId().toString());
            if (tenant != null && tenant.getEmail() != null && !tenant.getEmail().isBlank()) {
                tenantEmail = tenant.getEmail();
            }
        } catch (Exception e) {
            log.warn("[RenewalScheduler] Cannot resolve tenant email contractId={} tenantId={}: {}",
                    contract.getId(), contract.getUserId(), e.getMessage());
        }

        if (tenantEmail == null || tenantEmail.isBlank()) {
            log.warn("[RenewalScheduler] Skip email contractId={} reason=tenantEmail blank", contract.getId());
            return;
        }

        kafka.send("notification-email",
                contract.getId().toString(),
                SendEmailEvent.builder()
                        .to(tenantEmail)
                        .templateCode("contract_renewal_reminder")
                        .messageId(UUID.randomUUID().toString())
                        .params(Map.of(
                                "tenantName", contract.getTenantName() != null ? contract.getTenantName() : "",
                                "contractId", contract.getId().toString().substring(0, 8).toUpperCase(),
                                "daysRemaining", String.valueOf(daysUntilEnd),
                                "endDate", DMY.format(contract.getEndAt()),
                                "openForNew", daysUntilEnd <= 30
                        ))
                        .build());
    }

}
