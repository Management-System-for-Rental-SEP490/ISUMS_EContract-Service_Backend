package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.PowerCutConfirmedEvent;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PowerCutServiceImpl")
class PowerCutServiceImplTest {

    @Mock private EContractRepository contractRepo;
    @Mock private KafkaTemplate<String, Object> kafka;

    @InjectMocks private PowerCutServiceImpl service;

    private UUID contractId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        contractId = UUID.randomUUID();
        actorId = UUID.randomUUID();
    }

    private EContract contract(EContractStatus status, boolean hasPowerCutClause) {
        return EContract.builder()
                .id(contractId).userId(UUID.randomUUID()).houseId(UUID.randomUUID())
                .status(status).hasPowerCutClause(hasPowerCutClause).build();
    }

    @Nested
    @DisplayName("confirmPowerCut")
    class ConfirmPowerCut {

        @Test
        @DisplayName("publishes event with executeAt +24h when all pre-conditions met (IN_PROGRESS)")
        void happyInProgress() {
            EContract contract = contract(EContractStatus.IN_PROGRESS, true);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract));

            Instant before = Instant.now();
            service.confirmPowerCut(contractId, actorId);
            Instant after = Instant.now();

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(kafka).send(eq("contract.power-cut-confirmed"), anyString(), cap.capture());
            PowerCutConfirmedEvent event = (PowerCutConfirmedEvent) cap.getValue();
            assertThat(event.getContractId()).isEqualTo(contractId);
            assertThat(event.getConfirmedBy()).isEqualTo(actorId);
            assertThat(event.getExecuteAt()).isBetween(
                    before.plus(23, ChronoUnit.HOURS),
                    after.plus(25, ChronoUnit.HOURS));
        }

        @Test
        @DisplayName("accepts COMPLETED status as active")
        void completedAlsoAllowed() {
            when(contractRepo.findById(contractId))
                    .thenReturn(Optional.of(contract(EContractStatus.COMPLETED, true)));

            service.confirmPowerCut(contractId, actorId);

            verify(kafka).send(eq("contract.power-cut-confirmed"), anyString(), any(PowerCutConfirmedEvent.class));
        }

        @Test
        @DisplayName("throws NotFoundException when contract missing")
        void notFound() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmPowerCut(contractId, actorId))
                    .isInstanceOf(NotFoundException.class);
            verifyNoInteractions(kafka);
        }

        @Test
        @DisplayName("throws BusinessException when contract not in active status")
        void notActive() {
            when(contractRepo.findById(contractId))
                    .thenReturn(Optional.of(contract(EContractStatus.DRAFT, true)));

            assertThatThrownBy(() -> service.confirmPowerCut(contractId, actorId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không đang hoạt động");
            verifyNoInteractions(kafka);
        }

        @Test
        @DisplayName("throws BusinessException when contract has no power-cut clause")
        void noPowerCutClause() {
            when(contractRepo.findById(contractId))
                    .thenReturn(Optional.of(contract(EContractStatus.IN_PROGRESS, false)));

            assertThatThrownBy(() -> service.confirmPowerCut(contractId, actorId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("không có điều khoản cắt điện");
            verifyNoInteractions(kafka);
        }

        @Test
        @DisplayName("treats null hasPowerCutClause as no-clause (safe default)")
        void nullClauseTreatedAsFalse() {
            EContract c = contract(EContractStatus.IN_PROGRESS, false);
            c.setHasPowerCutClause(null);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.confirmPowerCut(contractId, actorId))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // helper: Mockito.any(PowerCutConfirmedEvent.class)
    private static <T> T any(Class<T> clazz) {
        return org.mockito.ArgumentMatchers.any(clazz);
    }
}
