package com.isums.contractservice.schedulers;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.LandlordProfile;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.ContractDepositExpiredEvent;
import com.isums.contractservice.domains.events.SendEmailEvent;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.LandlordProfileRepository;
import com.isums.contractservice.services.OutboxPublisher;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContractDepositExpiryScheduler {

    private final EContractRepository contractRepo;
    private final LandlordProfileRepository landlordRepo;
    private final OutboxPublisher outboxPublisher;
    private final UserGrpcClient userGrpc;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy").withZone(VN);
    private static final NumberFormat VND =
            NumberFormat.getNumberInstance(Locale.of("vi", "VN"));

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void processExpiredDeposits() {
        Instant now = Instant.now();
        List<EContract> expired = contractRepo.findExpiredDepositContracts(now);
        if (expired.isEmpty()) return;

        log.info("[DepositExpiry] Found {} contracts past deposit deadline", expired.size());
        for (EContract contract : expired) {
            try {
                cancelOne(contract, now);
            } catch (Exception e) {
                log.error("[DepositExpiry] Failed contractId={}: {}",
                        contract.getId(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void cancelOne(EContract contract, Instant now) {
        EContract fresh = contractRepo.findById(contract.getId()).orElse(null);
        if (fresh == null) return;
        if (fresh.getStatus() != EContractStatus.COMPLETED) return;

        fresh.getStatus().validateTransition(EContractStatus.CANCELLED_BY_LANDLORD);
        fresh.setStatus(EContractStatus.CANCELLED_BY_LANDLORD);
        fresh.setDepositDueAt(null);
        contractRepo.save(fresh);

        String tenantEmail = null;
        String tenantName = null;
        try {
            UserResponse u = userGrpc.getUserById(fresh.getUserId().toString());
            if (u != null) {
                tenantEmail = u.getEmail();
                tenantName = u.getName();
            }
        } catch (Exception e) {
            log.warn("[DepositExpiry] tenant lookup failed userId={}: {}",
                    fresh.getUserId(), e.getMessage());
        }

        String contractNo = fresh.getDocumentNo() != null
                ? fresh.getDocumentNo()
                : fresh.getId().toString().substring(0, 8).toUpperCase();
        Long depositAmount = fresh.getDepositAmount();
        String messageId = UUID.randomUUID().toString();

        ContractDepositExpiredEvent event = ContractDepositExpiredEvent.builder()
                .contractId(fresh.getId())
                .tenantId(fresh.getUserId())
                .houseId(fresh.getHouseId())
                .landlordId(fresh.getCreatedBy())
                .tenantEmail(tenantEmail)
                .tenantName(tenantName)
                .contractNo(contractNo)
                .depositAmount(depositAmount)
                .depositDueAt(contract.getDepositDueAt())
                .expiredAt(now)
                .messageId(messageId)
                .build();

        outboxPublisher.enqueue(
                "contract.deposit-expired",
                fresh.getId().toString(),
                event,
                messageId);

        sendTenantEmail(tenantEmail, tenantName, contractNo, depositAmount);
        sendLandlordEmail(fresh.getCreatedBy(), contractNo, tenantName, depositAmount);

        log.info("[DepositExpiry] CANCELLED contractId={} contractNo={} tenant={}",
                fresh.getId(), contractNo, tenantEmail);
    }

    private void sendTenantEmail(String email, String name, String contractNo, Long amount) {
        if (email == null || email.isBlank()) return;
        outboxPublisher.enqueue(
                "notification-email",
                contractNo,
                SendEmailEvent.builder()
                        .to(email)
                        .templateCode("CONTRACT_DEPOSIT_EXPIRED_TENANT")
                        .params(Map.of(
                                "tenantName", name != null ? name : "",
                                "contractNo", contractNo,
                                "depositAmount", formatVnd(amount)
                        ))
                        .messageId(UUID.randomUUID().toString())
                        .build());
    }

    private void sendLandlordEmail(UUID landlordId, String contractNo, String tenantName, Long amount) {
        if (landlordId == null) return;
        LandlordProfile profile = landlordRepo.findByUserId(landlordId).orElse(null);
        String email = null;
        String name = null;
        if (profile != null) {
            email = profile.getEmail();
            name = profile.getFullName();
        }
        if (email == null || email.isBlank()) {
            try {
                UserResponse u = userGrpc.getUserById(landlordId.toString());
                if (u != null) {
                    email = u.getEmail();
                    if (name == null || name.isBlank()) name = u.getName();
                }
            } catch (Exception e) {
                log.warn("[DepositExpiry] landlord lookup failed userId={}: {}",
                        landlordId, e.getMessage());
            }
        }
        if (email == null || email.isBlank()) return;
        outboxPublisher.enqueue(
                "notification-email",
                contractNo,
                SendEmailEvent.builder()
                        .to(email)
                        .templateCode("CONTRACT_DEPOSIT_EXPIRED_LANDLORD")
                        .params(Map.of(
                                "landlordName", name != null ? name : "",
                                "contractNo", contractNo,
                                "tenantName", tenantName != null ? tenantName : "",
                                "depositAmount", formatVnd(amount)
                        ))
                        .messageId(UUID.randomUUID().toString())
                        .build());
    }

    private static String formatVnd(Long amount) {
        if (amount == null) return "0 ₫";
        return VND.format(amount) + " ₫";
    }
}
