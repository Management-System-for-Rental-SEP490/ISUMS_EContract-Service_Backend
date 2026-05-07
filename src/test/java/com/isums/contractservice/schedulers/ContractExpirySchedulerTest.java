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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractExpiryScheduler")
class ContractExpirySchedulerTest {

    @Mock private EContractRepository contractRepo;
    @Mock private ContractTerminationService terminationService;

    @InjectMocks private ContractExpiryScheduler scheduler;

    private EContract expiredContract() {
        return EContract.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).houseId(UUID.randomUUID())
                .createdBy(UUID.randomUUID()).status(EContractStatus.IN_PROGRESS)
                .endAt(Instant.now().minusSeconds(60)).build();
    }

    @Test
    @DisplayName("processes each expired contract")
    void processesAll() {
        EContract c1 = expiredContract();
        EContract c2 = expiredContract();
        when(contractRepo.findByStatusInAndEndAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(c1, c2));

        scheduler.processExpiredContracts();

        verify(terminationService).handleExpiredContract(c1);
        verify(terminationService).handleExpiredContract(c2);
    }

    @Test
    @DisplayName("continues processing after per-contract failure (error isolation)")
    void errorIsolation() {
        EContract bad = expiredContract();
        EContract good = expiredContract();
        when(contractRepo.findByStatusInAndEndAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("downstream")).when(terminationService).handleExpiredContract(bad);

        scheduler.processExpiredContracts();

        verify(terminationService).handleExpiredContract(good);
    }

    @Test
    @DisplayName("no work when no expired contracts")
    void noWork() {
        when(contractRepo.findByStatusInAndEndAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of());

        scheduler.processExpiredContracts();

        verifyNoInteractions(terminationService);
    }
}
