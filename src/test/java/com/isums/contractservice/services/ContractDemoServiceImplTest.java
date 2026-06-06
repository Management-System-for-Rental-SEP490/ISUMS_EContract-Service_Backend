package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.ContractDemoRequest;
import com.isums.contractservice.domains.dtos.ContractPaymentStatus;
import com.isums.contractservice.domains.entities.ContractDemoSession;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.ContractDemoScenario;
import com.isums.contractservice.domains.enums.ContractDemoStatus;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.grpcs.PaymentGrpcClient;
import com.isums.contractservice.infrastructures.repositories.ContractDemoSessionRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.schedulers.RenewalNotificationScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractDemoServiceImplTest {

    @Mock private EContractRepository contractRepository;
    @Mock private ContractDemoSessionRepository sessionRepository;
    @Mock private ContractTerminationService terminationService;
    @Mock private RenewalNotificationScheduler renewalScheduler;
    @Mock private PaymentGrpcClient paymentGrpcClient;
    @InjectMocks private ContractDemoServiceImpl service;

    private EContract current;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        Instant endAt = Instant.parse("2026-08-01T17:00:00Z");
        current = EContract.builder()
                .id(UUID.randomUUID())
                .documentNo("HD-001")
                .houseId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .tenantName("Old tenant")
                .startAt(endAt.minus(365, ChronoUnit.DAYS))
                .endAt(endAt)
                .status(EContractStatus.COMPLETED)
                .depositStatus(DepositStatus.PAID)
                .createdBy(UUID.randomUUID())
                .build();
        when(contractRepository.findById(current.getId())).thenReturn(Optional.of(current));
        lenient().when(contractRepository.findNextContracts(
                eq(current.getHouseId()),
                eq(current.getUserId()),
                any(),
                any(),
                any())).thenReturn(List.of());
    }

    @Test
    void previewExpiredDescribesRealCheckoutLifecycle() {
        var preview = service.preview(
                current.getId(), ContractDemoScenario.EXPIRED, null);

        assertThat(preview.daysRemaining()).isNegative();
        assertThat(preview.actions()).anyMatch(v -> v.contains("CHECK_OUT"));
        assertThat(preview.warnings()).anyMatch(v -> v.contains("không đổi đồng hồ ECS"));
    }

    @Test
    void runExpiredPersistsSessionAndCallsRealTerminationService() {
        when(sessionRepository.findFirstByContractIdAndStatusOrderByStartedAtDesc(
                current.getId(), ContractDemoStatus.ACTIVE)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(invocation -> {
            ContractDemoSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(UUID.randomUUID());
            }
            return session;
        });

        var result = service.run(new ContractDemoRequest(
                current.getId(),
                ContractDemoScenario.EXPIRED,
                null,
                "HD-001"), actorId);

        verify(terminationService).handleExpiredContract(current);
        verify(renewalScheduler, never()).simulateContractMilestone(any(), any(), any());
        assertThat(result.sessionStatus()).isEqualTo(ContractDemoStatus.ACTIVE);
        assertThat(result.sessionId()).isNotNull();
    }

    @Test
    void runD30PublishesRealRenewalMilestoneAndCompletesSession() {
        when(sessionRepository.findFirstByContractIdAndStatusOrderByStartedAtDesc(
                current.getId(), ContractDemoStatus.ACTIVE)).thenReturn(Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(invocation -> {
            ContractDemoSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(UUID.randomUUID());
            }
            return session;
        });

        var result = service.run(new ContractDemoRequest(
                current.getId(),
                ContractDemoScenario.D30,
                null,
                current.getId().toString().substring(0, 8)), actorId);

        verify(renewalScheduler).simulateContractMilestone(
                eq(current), any(), any());
        verify(terminationService, never()).handleExpiredContract(any());
        assertThat(result.sessionStatus()).isEqualTo(ContractDemoStatus.COMPLETED);
        assertThat(result.daysRemaining()).isEqualTo(30);
    }

    @Test
    void rejectsWrongConfirmation() {
        assertThatThrownBy(() -> service.run(new ContractDemoRequest(
                current.getId(),
                ContractDemoScenario.EXPIRED,
                null,
                "WRONG"), actorId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Mã xác nhận");
    }
}
