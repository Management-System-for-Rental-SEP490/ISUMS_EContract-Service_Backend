package com.isums.contractservice.schedulers;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutoForceTerminationScheduler")
class AutoForceTerminationSchedulerTest {

    @Mock private EContractRepository contractRepo;
    @Mock private ContractTerminationService terminationService;

    @InjectMocks private AutoForceTerminationScheduler scheduler;

    private EContract contract(UUID id, Instant requestedAt) {
        EContract c = EContract.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .houseId(UUID.randomUUID())
                .status(EContractStatus.IN_PROGRESS)
                .build();
        c.setTerminationRequestedAt(requestedAt);
        return c;
    }

    @Test
    @DisplayName("auto-terminates contracts stuck > 7 days after termination request")
    void autoTerminatesStale() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        EContract c1 = contract(id1, Instant.now().minus(8, ChronoUnit.DAYS));
        EContract c2 = contract(id2, Instant.now().minus(15, ChronoUnit.DAYS));

        when(contractRepo.findByStatusAndTerminationRequestedAtBefore(
                eq(EContractStatus.IN_PROGRESS), any(Instant.class)))
                .thenReturn(List.of(c1, c2));

        scheduler.autoForceTerminate();

        verify(terminationService).confirmTerminationOverdue(eq(id1), any(UUID.class));
        verify(terminationService).confirmTerminationOverdue(eq(id2), any(UUID.class));
    }

    @Test
    @DisplayName("uses zero-UUID as system actor")
    void usesSystemActor() {
        UUID id = UUID.randomUUID();
        EContract c = contract(id, Instant.now().minus(8, ChronoUnit.DAYS));
        UUID expectedActor = new UUID(0L, 0L);

        when(contractRepo.findByStatusAndTerminationRequestedAtBefore(any(), any()))
                .thenReturn(List.of(c));

        scheduler.autoForceTerminate();

        verify(terminationService).confirmTerminationOverdue(id, expectedActor);
    }

    @Test
    @DisplayName("no-op when no stale contracts")
    void noStale() {
        when(contractRepo.findByStatusAndTerminationRequestedAtBefore(any(), any()))
                .thenReturn(List.of());

        scheduler.autoForceTerminate();

        verify(terminationService, never()).confirmTerminationOverdue(any(), any());
    }

    @Test
    @DisplayName("isolates failures — one contract failing does not block others")
    void isolatesFailure() {
        UUID idBad = UUID.randomUUID();
        UUID idOk = UUID.randomUUID();
        EContract bad = contract(idBad, Instant.now().minus(8, ChronoUnit.DAYS));
        EContract ok = contract(idOk, Instant.now().minus(8, ChronoUnit.DAYS));

        when(contractRepo.findByStatusAndTerminationRequestedAtBefore(any(), any()))
                .thenReturn(List.of(bad, ok));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(terminationService).confirmTerminationOverdue(eq(idBad), any());

        scheduler.autoForceTerminate();

        verify(terminationService).confirmTerminationOverdue(eq(idOk), any(UUID.class));
        verify(terminationService, times(2)).confirmTerminationOverdue(any(), any());
    }
}
