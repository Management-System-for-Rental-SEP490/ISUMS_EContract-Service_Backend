package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.ContractDemoPreview;
import com.isums.contractservice.domains.dtos.ContractDemoRequest;
import com.isums.contractservice.domains.dtos.ContractPaymentStatus;
import com.isums.contractservice.domains.entities.ContractDemoSession;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.ContractDemoScenario;
import com.isums.contractservice.domains.enums.ContractDemoStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.ContractDemoService;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.grpcs.PaymentGrpcClient;
import com.isums.contractservice.infrastructures.repositories.ContractDemoSessionRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.schedulers.RenewalNotificationScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractDemoServiceImpl implements ContractDemoService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private static final List<EContractStatus> DEMO_SOURCE_STATUSES =
            List.of(EContractStatus.IN_PROGRESS, EContractStatus.COMPLETED);
    private static final List<EContractStatus> NEXT_CONTRACT_STATUSES =
            List.of(EContractStatus.COMPLETED);
    private static final Set<Long> RENEWAL_MILESTONES =
            Set.of(60L, 30L, 14L, 7L, 3L, 1L, 0L);

    private final EContractRepository contractRepository;
    private final ContractDemoSessionRepository sessionRepository;
    private final ContractTerminationService terminationService;
    private final RenewalNotificationScheduler renewalScheduler;
    private final PaymentGrpcClient paymentGrpcClient;

    @Override
    @Transactional(readOnly = true)
    public ContractDemoPreview preview(
            UUID contractId,
            ContractDemoScenario scenario,
            Instant customEffectiveAt) {
        EContract contract = requireContract(contractId);
        Instant effectiveAt = resolveEffectiveAt(contract, scenario, customEffectiveAt);
        return buildPreview(contract, scenario, effectiveAt, null);
    }

    @Override
    @Transactional
    public ContractDemoPreview run(ContractDemoRequest request, UUID actorId) {
        EContract contract = requireContract(request.contractId());
        validateConfirmation(contract, request.confirmation());
        if (!DEMO_SOURCE_STATUSES.contains(contract.getStatus())) {
            throw new BusinessException(
                    "DEMO_CONTRACT_STATUS_INVALID",
                    "Demo chỉ chạy với hợp đồng đang ký hoặc đã ký. Trạng thái hiện tại: "
                            + contract.getStatus());
        }

        Instant now = Instant.now();
        sessionRepository
                .findFirstByContractIdAndStatusOrderByStartedAtDesc(
                        contract.getId(), ContractDemoStatus.ACTIVE)
                .ifPresent(existing -> {
                    if (existing.getExpiresAt().isAfter(now)) {
                        throw new BusinessException(
                                "DEMO_SESSION_ACTIVE",
                                "Hợp đồng đang có một phiên demo hoạt động");
                    }
                    existing.setStatus(ContractDemoStatus.CANCELLED);
                    existing.setCompletedAt(now);
                    sessionRepository.save(existing);
                    sessionRepository.flush();
                });

        Instant effectiveAt = resolveEffectiveAt(
                contract, request.scenario(), request.customEffectiveAt());
        ContractDemoSession session = sessionRepository.save(ContractDemoSession.builder()
                .contractId(contract.getId())
                .scenario(request.scenario())
                .status(ContractDemoStatus.ACTIVE)
                .effectiveAt(effectiveAt)
                .startedBy(actorId)
                .startedAt(now)
                .expiresAt(now.plus(SESSION_TTL))
                .build());

        ContractDemoPreview preview = buildPreview(
                contract, request.scenario(), effectiveAt, session);
        if (preview.daysRemaining() >= 0
                && !RENEWAL_MILESTONES.contains(preview.daysRemaining())) {
            throw new BusinessException(
                    "DEMO_MILESTONE_UNSUPPORTED",
                    "Mốc trước hết hạn phải là D-60, D-30, D-14, D-7, D-3, D-1 hoặc D-0");
        }
        if (preview.daysRemaining() < 0) {
            terminationService.handleExpiredContract(contract);
            log.info("[ContractDemo] Expiry lifecycle started sessionId={} contractId={} effectiveAt={}",
                    session.getId(), contract.getId(), effectiveAt);
        } else {
            renewalScheduler.simulateContractMilestone(
                    contract, effectiveAt.atZone(VN).toLocalDate(), session.getId());
            session.setStatus(ContractDemoStatus.COMPLETED);
            session.setCompletedAt(Instant.now());
            sessionRepository.save(session);
            log.info("[ContractDemo] Renewal milestone completed sessionId={} contractId={} days={}",
                    session.getId(), contract.getId(), preview.daysRemaining());
        }

        return buildPreview(contract, request.scenario(), effectiveAt, session);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractDemoPreview getActive(UUID contractId) {
        ContractDemoSession session = sessionRepository
                .findFirstByContractIdAndStatusOrderByStartedAtDesc(
                        contractId, ContractDemoStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("Không có phiên demo đang hoạt động"));
        EContract contract = requireContract(contractId);
        return buildPreview(contract, session.getScenario(), session.getEffectiveAt(), session);
    }

    @Override
    @Transactional
    public void cancel(UUID contractId, UUID actorId) {
        ContractDemoSession session = sessionRepository
                .findFirstByContractIdAndStatusOrderByStartedAtDesc(
                        contractId, ContractDemoStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("Không có phiên demo đang hoạt động"));
        session.setStatus(ContractDemoStatus.CANCELLED);
        session.setCompletedAt(Instant.now());
        sessionRepository.save(session);
        log.warn("[ContractDemo] Session cancelled without business rollback sessionId={} contractId={} by={}",
                session.getId(), contractId, actorId);
    }

    private EContract requireContract(UUID contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));
    }

    private Instant resolveEffectiveAt(
            EContract contract,
            ContractDemoScenario scenario,
            Instant customEffectiveAt) {
        if (contract.getEndAt() == null) {
            throw new BusinessException("DEMO_END_DATE_REQUIRED", "Hợp đồng chưa có ngày kết thúc");
        }
        if (scenario == ContractDemoScenario.CUSTOM) {
            if (customEffectiveAt == null) {
                throw new BusinessException(
                        "DEMO_EFFECTIVE_TIME_REQUIRED",
                        "Phải chọn thời gian mô phỏng");
            }
            return customEffectiveAt;
        }
        if (scenario == ContractDemoScenario.EXPIRED) {
            return contract.getEndAt().plusSeconds(60);
        }
        return contract.getEndAt().minus(
                scenario.daysRemaining(), ChronoUnit.DAYS);
    }

    private void validateConfirmation(EContract contract, String confirmation) {
        String actual = confirmation == null ? "" : confirmation.trim();
        String shortId = contract.getId().toString().substring(0, 8);
        boolean matchesDocument = contract.getDocumentNo() != null
                && contract.getDocumentNo().equalsIgnoreCase(actual);
        if (!matchesDocument && !shortId.equalsIgnoreCase(actual)) {
            throw new BusinessException(
                    "DEMO_CONFIRMATION_INVALID",
                    "Mã xác nhận không khớp số hợp đồng hoặc 8 ký tự đầu của ID");
        }
    }

    private ContractDemoPreview buildPreview(
            EContract contract,
            ContractDemoScenario scenario,
            Instant effectiveAt,
            ContractDemoSession session) {
        LocalDate effectiveDate = effectiveAt.atZone(VN).toLocalDate();
        LocalDate endDate = contract.getEndAt().atZone(VN).toLocalDate();
        long daysRemaining = ChronoUnit.DAYS.between(effectiveDate, endDate);
        if (effectiveAt.isAfter(contract.getEndAt()) && daysRemaining >= 0) {
            daysRemaining = -1;
        }

        EContract next = findNextContract(contract);
        ContractPaymentStatus payment = resolvePaymentStatus(next);
        boolean nextSigned = next != null && next.getStatus() == EContractStatus.COMPLETED;
        boolean startReached = next != null && !effectiveAt.isBefore(
                next.getHandoverDate() != null ? next.getHandoverDate() : next.getStartAt());
        boolean ready = nextSigned && payment.depositPaid()
                && payment.firstRentPaid() && startReached;

        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (daysRemaining < 0) {
            actions.add("Chuyển hợp đồng cũ sang PENDING_TERMINATION");
            actions.add("Thu hồi quyền vào nhà của người thuê cũ");
            actions.add("Tạo kiểm tra CHECK_OUT thật cho staff");
            actions.add("Chờ manager APPROVED trước khi bàn giao nhà");
            if (next != null) {
                actions.add(ready
                        ? "Bàn giao ngay cho người thuê kế tiếp sau khi duyệt"
                        : "Giữ người thuê kế tiếp ở trạng thái chờ cho đến khi đủ điều kiện");
            }
        } else {
            actions.add("Phát nhắc gia hạn qua Kafka và email thật");
            if (daysRemaining == 30 || daysRemaining == 0) {
                actions.add("Mở cửa sổ ưu tiên/đặt cọc cho hợp đồng kế tiếp");
            }
        }
        warnings.add("Phiên demo không đổi đồng hồ ECS, JWT, OTP hoặc thời gian thanh toán");
        warnings.add("Hủy phiên chỉ dừng đồng hồ demo, không hoàn tác nghiệp vụ đã phát sinh");
        if (next == null) {
            warnings.add("Chưa có hợp đồng kế tiếp cho căn nhà này");
        } else if (!ready) {
            warnings.add("Người thuê kế tiếp chưa đủ điều kiện vào nhà tại thời gian mô phỏng");
        }

        return new ContractDemoPreview(
                session != null ? session.getId() : null,
                session != null ? session.getStatus() : null,
                scenario,
                Instant.now(),
                effectiveAt,
                daysRemaining,
                summary(contract),
                summary(next),
                next == null ? null : new ContractDemoPreview.NextTenantReadiness(
                        nextSigned,
                        payment.depositPaid(),
                        payment.firstRentPaid(),
                        startReached,
                        ready),
                List.copyOf(actions),
                List.copyOf(warnings));
    }

    private EContract findNextContract(EContract current) {
        Instant from = current.getStartAt() != null ? current.getStartAt() : Instant.EPOCH;
        return contractRepository.findNextContracts(
                        current.getHouseId(),
                        current.getUserId(),
                        NEXT_CONTRACT_STATUSES,
                        from,
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ContractPaymentStatus resolvePaymentStatus(EContract next) {
        if (next == null) {
            return ContractPaymentStatus.unavailable();
        }
        try {
            return paymentGrpcClient.getInvoiceStatus(next.getHouseId(), next.getUserId());
        } catch (Exception ex) {
            log.warn("[ContractDemo] Cannot resolve next tenant payment contractId={}: {}",
                    next.getId(), ex.getMessage());
            return ContractPaymentStatus.unavailable();
        }
    }

    private ContractDemoPreview.ContractSummary summary(EContract contract) {
        if (contract == null) {
            return null;
        }
        return new ContractDemoPreview.ContractSummary(
                contract.getId(),
                contract.getDocumentNo(),
                contract.getHouseId(),
                contract.getUserId(),
                contract.getTenantName(),
                contract.getTenantEmail(),
                contract.getStartAt(),
                contract.getEndAt(),
                contract.getStatus(),
                contract.getDepositStatus());
    }
}
