package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.RenewalRequestBody;
import com.isums.contractservice.domains.dtos.RenewalRequestDto;
import com.isums.contractservice.domains.dtos.RenewalStatusDto;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.RenewalRequest;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.ForbiddenException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalServiceImpl")
class RenewalServiceImplTest {

    @Mock private EContractRepository contractRepo;
    @Mock private RenewalRequestRepository renewalRequestRepo;
    @Mock private UserGrpcClient userGrpcClient;
    @Mock private KafkaTemplate<String, Object> kafka;

    @InjectMocks private RenewalServiceImpl service;

    private UUID contractId;
    private UUID tenantId;
    private UUID managerId;
    private UUID houseId;

    @BeforeEach
    void setUp() {
        contractId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        managerId = UUID.randomUUID();
        houseId = UUID.randomUUID();
    }

    private EContract contract(EContractStatus status) {
        return EContract.builder()
                .id(contractId).userId(tenantId).houseId(houseId)
                .createdBy(managerId).tenantName("Alice")
                .endAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .status(status).build();
    }

    @Nested
    @DisplayName("requestRenewal")
    class RequestRenewal {

        @Test
        @DisplayName("creates PENDING request, flags hasCompeting=false when no competing deposits")
        void happy() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(renewalRequestRepo.findByContractIdAndStatusNotIn(eq(contractId), anyList()))
                    .thenReturn(Optional.empty());
            when(renewalRequestRepo.findByHouseIdAndStatusNotIn(eq(houseId), anyList()))
                    .thenReturn(Optional.empty());
            when(userGrpcClient.getUserById(managerId.toString()))
                    .thenReturn(UserResponse.newBuilder()
                            .setEmail("m@ex.com").setName("M").build());

            RenewalRequestDto dto = service.requestRenewal(contractId, tenantId, new RenewalRequestBody("please"));

            ArgumentCaptor<RenewalRequest> cap = ArgumentCaptor.forClass(RenewalRequest.class);
            verify(renewalRequestRepo).save(cap.capture());
            RenewalRequest saved = cap.getValue();
            assertThat(saved.getStatus()).isEqualTo(RenewalRequestStatus.PENDING_MANAGER_REVIEW);
            assertThat(saved.getTenantNote()).isEqualTo("please");
            assertThat(saved.getHasCompetingDeposit()).isFalse();
            assertThat(saved.getTenantUserId()).isEqualTo(tenantId);
            assertThat(dto).isNotNull();
        }

        @Test
        @DisplayName("flags hasCompeting=true when another active request exists for the house")
        void hasCompeting() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            UUID otherContractId = UUID.randomUUID();
            RenewalRequest competing = RenewalRequest.builder()
                    .id(UUID.randomUUID()).contractId(otherContractId).houseId(houseId).build();

            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(renewalRequestRepo.findByContractIdAndStatusNotIn(eq(contractId), anyList()))
                    .thenReturn(Optional.empty());
            when(renewalRequestRepo.findByHouseIdAndStatusNotIn(eq(houseId), anyList()))
                    .thenReturn(Optional.of(competing));
            when(userGrpcClient.getUserById(managerId.toString()))
                    .thenReturn(UserResponse.newBuilder().setEmail("m@ex.com").build());

            service.requestRenewal(contractId, tenantId, new RenewalRequestBody(null));

            ArgumentCaptor<RenewalRequest> cap = ArgumentCaptor.forClass(RenewalRequest.class);
            verify(renewalRequestRepo).save(cap.capture());
            assertThat(cap.getValue().getHasCompetingDeposit()).isTrue();
        }

        @Test
        @DisplayName("throws NotFoundException when contract missing")
        void contractMissing() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestRenewal(contractId, tenantId, new RenewalRequestBody(null)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("throws BusinessException when contract not IN_PROGRESS")
        void contractNotActive() {
            when(contractRepo.findById(contractId))
                    .thenReturn(Optional.of(contract(EContractStatus.DRAFT)));

            assertThatThrownBy(() -> service.requestRenewal(contractId, tenantId, new RenewalRequestBody(null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đang hoạt động");
        }

        @Test
        @DisplayName("throws ForbiddenException when caller is not the tenant of the contract")
        void notTenant() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            UUID impostor = UUID.randomUUID();

            assertThatThrownBy(() -> service.requestRenewal(contractId, impostor, new RenewalRequestBody(null)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("throws BusinessException when renewal request already pending")
        void alreadyPending() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(renewalRequestRepo.findByContractIdAndStatusNotIn(eq(contractId), anyList()))
                    .thenReturn(Optional.of(RenewalRequest.builder()
                            .id(UUID.randomUUID()).contractId(contractId).build()));

            assertThatThrownBy(() -> service.requestRenewal(contractId, tenantId, new RenewalRequestBody(null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đã gửi yêu cầu");
            verify(renewalRequestRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("declineRenewal")
    class Decline {

        @Test
        @DisplayName("updates to DECLINED_BY_MANAGER, sets reason + resolvedAt, sends email")
        void happy() {
            UUID renewalId = UUID.randomUUID();
            RenewalRequest request = RenewalRequest.builder()
                    .id(renewalId).contractId(contractId)
                    .status(RenewalRequestStatus.PENDING_MANAGER_REVIEW).build();

            when(renewalRequestRepo.findById(renewalId)).thenReturn(Optional.of(request));
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract(EContractStatus.IN_PROGRESS)));
            when(userGrpcClient.getUserById(tenantId.toString()))
                    .thenReturn(UserResponse.newBuilder().setEmail("alice@example.com").build());

            service.declineRenewal(renewalId, managerId, "wrong timing");

            assertThat(request.getStatus()).isEqualTo(RenewalRequestStatus.DECLINED_BY_MANAGER);
            assertThat(request.getDeclineReason()).isEqualTo("wrong timing");
            assertThat(request.getResolvedAt()).isNotNull();
            verify(renewalRequestRepo).save(request);
            verify(kafka).send(eq("notification-email"), any());
        }

        @Test
        @DisplayName("throws NotFoundException when renewal request missing")
        void missing() {
            UUID renewalId = UUID.randomUUID();
            when(renewalRequestRepo.findById(renewalId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.declineRenewal(renewalId, managerId, "r"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("markNewContractDrafted")
    class MarkDrafted {

        @Test
        @DisplayName("updates status NEW_CONTRACT_DRAFTED and links newContractId")
        void happy() {
            UUID renewalId = UUID.randomUUID();
            UUID newContractId = UUID.randomUUID();
            RenewalRequest request = RenewalRequest.builder()
                    .id(renewalId).status(RenewalRequestStatus.PENDING_MANAGER_REVIEW).build();
            when(renewalRequestRepo.findById(renewalId)).thenReturn(Optional.of(request));

            service.markNewContractDrafted(renewalId, newContractId);

            assertThat(request.getStatus()).isEqualTo(RenewalRequestStatus.NEW_CONTRACT_DRAFTED);
            assertThat(request.getNewContractId()).isEqualTo(newContractId);
            verify(renewalRequestRepo).save(request);
        }

        @Test
        @DisplayName("throws NotFoundException when renewal missing")
        void missing() {
            when(renewalRequestRepo.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markNewContractDrafted(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("markCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("updates status COMPLETED and sets resolvedAt")
        void happy() {
            UUID renewalId = UUID.randomUUID();
            RenewalRequest request = RenewalRequest.builder()
                    .id(renewalId).status(RenewalRequestStatus.NEW_CONTRACT_DRAFTED).build();
            when(renewalRequestRepo.findById(renewalId)).thenReturn(Optional.of(request));

            service.markCompleted(renewalId);

            assertThat(request.getStatus()).isEqualTo(RenewalRequestStatus.COMPLETED);
            assertThat(request.getResolvedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("getRenewalStatus")
    class GetStatus {

        @Test
        @DisplayName("returns canRequest=true when no active request and contract IN_PROGRESS")
        void canRequest() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(renewalRequestRepo.findByContractIdAndStatusNotIn(eq(contractId), anyList()))
                    .thenReturn(Optional.empty());
            when(renewalRequestRepo.findByHouseIdAndStatusNotIn(eq(houseId), anyList()))
                    .thenReturn(Optional.empty());

            RenewalStatusDto dto = service.getRenewalStatus(contractId, tenantId);
            assertThat(dto.isCanRequestRenewal()).isTrue();
            assertThat(dto.isHasActiveRequest()).isFalse();
            assertThat(dto.isHasCompetingDeposit()).isFalse();
            assertThat(dto.getDaysUntilExpiry()).isGreaterThan(0);
            assertThat(dto.isWindowOpenForNewTenants()).isFalse();
        }

        @Test
        @DisplayName("returns canRequest=false + activeRequestStatus when pending request exists")
        void hasActive() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            RenewalRequest req = RenewalRequest.builder()
                    .id(UUID.randomUUID()).contractId(contractId)
                    .status(RenewalRequestStatus.PENDING_MANAGER_REVIEW).build();
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(renewalRequestRepo.findByContractIdAndStatusNotIn(eq(contractId), anyList()))
                    .thenReturn(Optional.of(req));
            when(renewalRequestRepo.findByHouseIdAndStatusNotIn(eq(houseId), anyList()))
                    .thenReturn(Optional.empty());

            RenewalStatusDto dto = service.getRenewalStatus(contractId, tenantId);
            assertThat(dto.isCanRequestRenewal()).isFalse();
            assertThat(dto.isHasActiveRequest()).isTrue();
            assertThat(dto.getActiveRequestStatus()).isEqualTo("PENDING_MANAGER_REVIEW");
        }

        @Test
        @DisplayName("windowOpenForNewTenants=true when contract already expired (daysUntilExpiry<=0)")
        void windowOpen() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setEndAt(Instant.now().minus(5, ChronoUnit.DAYS));
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(renewalRequestRepo.findByContractIdAndStatusNotIn(eq(contractId), anyList()))
                    .thenReturn(Optional.empty());
            when(renewalRequestRepo.findByHouseIdAndStatusNotIn(eq(houseId), anyList()))
                    .thenReturn(Optional.empty());

            RenewalStatusDto dto = service.getRenewalStatus(contractId, tenantId);
            assertThat(dto.isWindowOpenForNewTenants()).isTrue();
            assertThat(dto.getDaysUntilExpiry()).isLessThanOrEqualTo(0);
        }

        @Test
        @DisplayName("throws NotFoundException when contract missing")
        void notFound() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getRenewalStatus(contractId, tenantId))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
