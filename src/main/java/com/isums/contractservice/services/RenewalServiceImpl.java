package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.RenewalRequestDto;
import com.isums.contractservice.domains.dtos.RenewalRequestBody;
import com.isums.contractservice.domains.dtos.RenewalStatusDto;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.RenewalRequest;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.domains.events.InspectionScheduledEvent;
import com.isums.contractservice.domains.events.SendEmailEvent;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.ForbiddenException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.RenewalService;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RenewalServiceImpl implements RenewalService {

    private final EContractRepository contractRepo;
    private final RenewalRequestRepository renewalRequestRepo;
    private final UserGrpcClient userGrpcClient;
    private final KafkaTemplate<String, Object> kafka;

    @Transactional
    public RenewalRequestDto requestRenewal(UUID contractId, UUID tenantUserId, RenewalRequestBody body) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (contract.getStatus() != EContractStatus.IN_PROGRESS) {
            throw new BusinessException("Chỉ gia hạn được hợp đồng đang hoạt động");
        }

        if (!contract.getUserId().equals(tenantUserId)) {
            throw new ForbiddenException("Không có quyền gia hạn hợp đồng này");
        }

        renewalRequestRepo.findByContractIdAndStatusNotIn(
                contractId,
                List.of(RenewalRequestStatus.DECLINED_BY_MANAGER,
                        RenewalRequestStatus.CANCELLED_BY_TENANT)
        ).ifPresent(r -> {
            throw new BusinessException(
                    "Bạn đã gửi yêu cầu gia hạn, vui lòng chờ quản lý liên hệ");
        });

        boolean hasCompeting = renewalRequestRepo
                .findByHouseIdAndStatusNotIn(
                        contract.getHouseId(),
                        List.of(RenewalRequestStatus.DECLINED_BY_MANAGER,
                                RenewalRequestStatus.CANCELLED_BY_TENANT,
                                RenewalRequestStatus.COMPLETED)
                )
                .filter(r -> !r.getContractId().equals(contractId))
                .isPresent();

        RenewalRequest request = RenewalRequest.builder()
                .contractId(contractId)
                .houseId(contract.getHouseId())
                .tenantUserId(tenantUserId)
                .status(RenewalRequestStatus.PENDING_MANAGER_REVIEW)
                .tenantNote(body.note())
                .hasCompetingDeposit(hasCompeting)
                .build();

        renewalRequestRepo.save(request);

        notifyManagerRenewalRequest(contract, request);

        log.info("[Renewal] Request created contractId={} hasCompeting={}",
                contractId, hasCompeting);

        return RenewalRequestDto.from(request);
    }

    @Transactional
    public void declineRenewal(UUID renewalRequestId, UUID actorId, String reason) {
        RenewalRequest request = renewalRequestRepo.findById(renewalRequestId)
                .orElseThrow(() -> new NotFoundException("Renewal request not found"));

        request.setStatus(RenewalRequestStatus.DECLINED_BY_MANAGER);
        request.setDeclineReason(reason);
        request.setResolvedAt(Instant.now());
        renewalRequestRepo.save(request);

        EContract contract = contractRepo.findById(request.getContractId())
                .orElseThrow();

        UserResponse tenant = userGrpcClient.getUserById(contract.getUserId().toString());

        kafka.send("notification-email",
                SendEmailEvent.builder()
                        .to(tenant.getEmail())
                        .templateCode("renewal_declined")
                        .messageId(UUID.randomUUID().toString())
                        .params(Map.of(
                                "tenantName", contract.getTenantName(),
                                "contractId", contract.getId().toString()
                                        .substring(0, 8).toUpperCase(),
                                "reason", reason != null ? reason : "Không có lý do"
                        ))
                        .build());

        log.info("[Renewal] Declined renewalRequestId={}", renewalRequestId);
    }

    @Transactional
    public void markNewContractDrafted(UUID renewalRequestId, UUID newContractId) {
        RenewalRequest request = renewalRequestRepo.findById(renewalRequestId)
                .orElseThrow(() -> new NotFoundException("Renewal request not found"));

        request.setStatus(RenewalRequestStatus.NEW_CONTRACT_DRAFTED);
        request.setNewContractId(newContractId);
        renewalRequestRepo.save(request);
    }

    @Transactional
    public void markCompleted(UUID renewalRequestId) {
        RenewalRequest request = renewalRequestRepo.findById(renewalRequestId)
                .orElseThrow(() -> new NotFoundException("Renewal request not found"));

        request.setStatus(RenewalRequestStatus.COMPLETED);
        request.setResolvedAt(Instant.now());
        renewalRequestRepo.save(request);
    }

    public RenewalStatusDto getRenewalStatus(UUID contractId, UUID tenantUserId) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        long daysUntilExpiry = ChronoUnit.DAYS.between(
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),
                contract.getEndAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
        );

        Optional<RenewalRequest> activeRequest = renewalRequestRepo
                .findByContractIdAndStatusNotIn(
                        contractId,
                        List.of(RenewalRequestStatus.DECLINED_BY_MANAGER,
                                RenewalRequestStatus.CANCELLED_BY_TENANT)
                );

        boolean hasCompeting = renewalRequestRepo
                .findByHouseIdAndStatusNotIn(
                        contract.getHouseId(),
                        List.of(RenewalRequestStatus.DECLINED_BY_MANAGER,
                                RenewalRequestStatus.CANCELLED_BY_TENANT,
                                RenewalRequestStatus.COMPLETED)
                )
                .filter(r -> !r.getContractId().equals(contractId))
                .isPresent();

        return RenewalStatusDto.builder()
                .canRequestRenewal(!activeRequest.isPresent()
                        && contract.getStatus() == EContractStatus.IN_PROGRESS)
                .hasActiveRequest(activeRequest.isPresent())
                .activeRequestStatus(activeRequest
                        .map(r -> r.getStatus().name()).orElse(null))
                .hasCompetingDeposit(hasCompeting)
                .daysUntilExpiry(daysUntilExpiry)
                .windowOpenForNewTenants(daysUntilExpiry <= 0)
                .build();
    }

    private void notifyManagerRenewalRequest(EContract contract,
                                             RenewalRequest request) {
        try {
            UserResponse manager = userGrpcClient.getUserById(contract.getCreatedBy().toString());

            kafka.send("notification-email",
                    SendEmailEvent.builder()
                            .to(manager.getEmail())
                            .templateCode("renewal_request_received")
                            .messageId(UUID.randomUUID().toString())
                            .params(Map.of(
                                    "managerName", manager.getName(),
                                    "tenantName", contract.getTenantName(),
                                    "contractId", contract.getId().toString()
                                            .substring(0, 8).toUpperCase(),
                                    "hasCompetingDeposit",
                                    request.getHasCompetingDeposit()
                                            ? "Đã có khách khác đặt cọc phòng này"
                                            : "Chưa có khách nào đặt cọc",
                                    "note", request.getTenantNote() != null
                                            ? request.getTenantNote()
                                            : "Không có ghi chú"
                            ))
                            .build());

            kafka.send("contract.inspection.scheduled",
                    InspectionScheduledEvent.builder()
                            .build());

        } catch (Exception e) {
            log.error("[Renewal] Failed to notify manager contractId={}: {}",
                    contract.getId(), e.getMessage());
        }
    }
}
