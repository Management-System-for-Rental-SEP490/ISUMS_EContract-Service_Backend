package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.JobCreatedEvent;
import com.isums.contractservice.domains.events.SendEmailEvent;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.userservice.grpc.UserResponse;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractTerminationServiceimpl")
class ContractTerminationServiceimplTest {

    @Mock private EContractRepository contractRepo;
    @Mock private KafkaTemplate<String, Object> kafka;
    @Mock private UserGrpcClient userGrpcClient;

    @InjectMocks private ContractTerminationServiceimpl service;

    private UUID contractId;
    private UUID managerId;

    @BeforeEach
    void setUp() {
        contractId = UUID.randomUUID();
        managerId = UUID.randomUUID();
    }

    private EContract contract(EContractStatus status) {
        return EContract.builder()
                .id(contractId).userId(UUID.randomUUID()).houseId(UUID.randomUUID())
                .createdBy(managerId).tenantName("Alice")
                .status(status).build();
    }

    @Nested
    @DisplayName("handleExpiredContract")
    class HandleExpired {

        @Test
        @DisplayName("transitions to PENDING_TERMINATION, publishes job, notifies manager")
        void happy() {
            EContract c = contract(EContractStatus.IN_PROGRESS);

            UserResponse manager = UserResponse.newBuilder()
                    .setId(managerId.toString()).setName("Manager").setEmail("m@ex.com").build();
            when(userGrpcClient.getUserById(managerId.toString())).thenReturn(manager);

            service.handleExpiredContract(c);

            assertThat(c.getStatus()).isEqualTo(EContractStatus.PENDING_TERMINATION);
            verify(contractRepo).save(c);

            ArgumentCaptor<Object> jobCap = ArgumentCaptor.forClass(Object.class);
            verify(kafka).send(eq("job.created"), anyString(), jobCap.capture());
            JobCreatedEvent job = (JobCreatedEvent) jobCap.getValue();
            assertThat(job.getReferenceId()).isEqualTo(contractId);
            assertThat(job.getReferenceType()).isEqualTo("INSPECTION");
            assertThat(job.getType()).isEqualTo("CHECK_OUT");

            ArgumentCaptor<Object> mailCap = ArgumentCaptor.forClass(Object.class);
            verify(kafka).send(eq("notification-email"), mailCap.capture());
            SendEmailEvent mail = (SendEmailEvent) mailCap.getValue();
            assertThat(mail.getTo()).isEqualTo("m@ex.com");
            assertThat(mail.getTemplateCode()).isEqualTo("contract_expired_inspection_scheduled");
        }

        @Test
        @DisplayName("throws IllegalStateException when status transition invalid")
        void invalidTransition() {
            EContract c = contract(EContractStatus.DRAFT);

            assertThatThrownBy(() -> service.handleExpiredContract(c))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFT → PENDING_TERMINATION");
        }

        @Test
        @DisplayName("swallows manager notification failure (does not break termination flow)")
        void notifyFailsSwallowed() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(userGrpcClient.getUserById(any(String.class)))
                    .thenThrow(new RuntimeException("user-service down"));

            service.handleExpiredContract(c);

            assertThat(c.getStatus()).isEqualTo(EContractStatus.PENDING_TERMINATION);
            verify(kafka).send(eq("job.created"), anyString(), any());
        }
    }

    @Nested
    @DisplayName("confirmTerminationOverdue")
    class Confirm {

        @Test
        @DisplayName("happy path for IN_PROGRESS contract")
        void happyInProgress() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            UserResponse manager = UserResponse.newBuilder()
                    .setId(managerId.toString()).setName("M").setEmail("m@x.com").build();
            when(userGrpcClient.getUserById(managerId.toString())).thenReturn(manager);

            service.confirmTerminationOverdue(contractId, UUID.randomUUID());

            assertThat(c.getStatus()).isEqualTo(EContractStatus.PENDING_TERMINATION);
        }

        @Test
        @DisplayName("throws NotFoundException when contract missing")
        void missing() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmTerminationOverdue(contractId, UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("throws BusinessException for inactive status")
        void inactive() {
            when(contractRepo.findById(contractId))
                    .thenReturn(Optional.of(contract(EContractStatus.DRAFT)));

            assertThatThrownBy(() -> service.confirmTerminationOverdue(contractId, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
