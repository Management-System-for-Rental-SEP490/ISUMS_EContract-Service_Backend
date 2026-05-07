package com.isums.contractservice.schedulers;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookableWindowNotificationScheduler {

    private static final String TOPIC = "notification-email";
    private static final String TEMPLATE = "landlord_house_bookable_soon";
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(VN);

    private static final List<RenewalRequestStatus> RENEWAL_CLOSED = List.of(
            RenewalRequestStatus.DECLINED_BY_MANAGER,
            RenewalRequestStatus.CANCELLED_BY_TENANT,
            RenewalRequestStatus.COMPLETED);

    private final EContractRepository contractRepo;
    private final RenewalRequestRepository renewalRequestRepo;
    private final UserGrpcClient userGrpc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void notifyLandlordsOfBookableWindow() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(30, ChronoUnit.DAYS);

        List<EContract> contracts = contractRepo.findUnnotifiedBookableWindowContracts(
                EContractStatus.COMPLETED, now, cutoff);

        int sent = 0;
        for (EContract c : contracts) {
            try {
                if (renewalRequestRepo.existsByContractIdAndStatusNotIn(c.getId(), RENEWAL_CLOSED)) {
                    continue;
                }
                if (c.getCreatedBy() == null) continue;
                UserResponse landlord = userGrpc.getUserById(c.getCreatedBy().toString());
                if (landlord == null || landlord.getEmail() == null || landlord.getEmail().isBlank()) {
                    continue;
                }
                String payload = objectMapper.writeValueAsString(Map.of(
                        "messageId", UUID.randomUUID().toString(),
                        "to", landlord.getEmail(),
                        "templateCode", TEMPLATE,
                        "params", Map.of(
                                "landlordName", landlord.getName() != null ? landlord.getName() : "",
                                "houseId", c.getHouseId().toString(),
                                "contractId", c.getId().toString(),
                                "endDate", DMY.format(c.getEndAt()),
                                "daysRemaining", String.valueOf(
                                        Math.max(0, ChronoUnit.DAYS.between(
                                                now.atZone(VN).toLocalDate(),
                                                c.getEndAt().atZone(VN).toLocalDate())))
                        )));
                kafkaTemplate.send(TOPIC, c.getId().toString(), payload);
                c.setBookableWindowNotifiedAt(now);
                contractRepo.save(c);
                sent++;
            } catch (Exception e) {
                log.warn("[BookableWindow] notify failed contractId={}: {}",
                        c.getId(), e.getMessage());
            }
        }
        log.info("[BookableWindow] Notified {} landlords (out of {} candidates)",
                sent, contracts.size());
    }
}
