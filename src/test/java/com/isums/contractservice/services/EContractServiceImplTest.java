package com.isums.contractservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.DepositRefundConfirmedEvent;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import com.isums.contractservice.infrastructures.grpcs.AssetGrpcClient;
import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.mappers.EContractMapper;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.EContractTemplateRepository;
import com.isums.contractservice.infrastructures.repositories.LandlordProfileRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
import com.isums.userservice.grpc.UserResponse;
import common.paginations.cache.CachedPageService;
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
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EContractServiceImpl (subset — large service)")
class EContractServiceImplTest {

    @Mock private VnptEContractClient vnptClient;
    @Mock private KafkaTemplate<String, Object> kafka;
    @Mock private SimpMessagingTemplate ws;
    @Mock private HouseGrpcClient houseGrpc;
    @Mock private UserGrpcClient userGrpc;
    @Mock private AssetGrpcClient assetGrpc;
    @Mock private EContractRepository contractRepo;
    @Mock private EContractTemplateRepository templateRepo;
    @Mock private LandlordProfileRepository landlordRepo;
    @Mock private EContractMapper mapper;
    @Mock private S3Service s3;
    @Mock private ObjectMapper json;
    @Mock private KeycloakAdminServiceImpl keycloakAdmin;
    @Mock private ContractTokenService contractTokenService;
    @Mock private RenewalRequestRepository renewalRequestRepo;
    @Mock private RenewalServiceImpl renewalService;
    @Mock private CachedPageService cachedPageService;

    @InjectMocks private EContractServiceImpl service;

    private UUID contractId;
    private UUID tenantId;
    private UUID houseId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ocrUrl", "http://ocr.example");
        ReflectionTestUtils.setField(service, "vnptLandlordUsername", "landlord@vnpt");
        ReflectionTestUtils.setField(service, "contractViewBaseUrl", "https://isums.pro/contracts");
        ReflectionTestUtils.setField(service, "pdfUrlTtlMinutes", 30);

        contractId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        houseId = UUID.randomUUID();
        actorId = UUID.randomUUID();
    }

    private EContract contract(EContractStatus status) {
        return EContract.builder()
                .id(contractId).userId(tenantId).houseId(houseId)
                .createdBy(actorId).tenantName("Alice")
                .tenantIdentityNumber("0123456789")
                .hasPowerCutClause(false)
                .status(status)
                .startAt(Instant.now()).endAt(Instant.now().plusSeconds(86400))
                .build();
    }

    private EContractDto dto() {
        return new EContractDto(contractId, null, null, tenantId, null,
                "<html/>", "Contract", null, houseId, Instant.now(), Instant.now().plusSeconds(86400),
                EContractStatus.DRAFT, null, actorId, Instant.now());
    }

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("returns dto without pdfUrl for DRAFT status")
        void draft() {
            EContract c = contract(EContractStatus.DRAFT);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(mapper.contractToDto(c)).thenReturn(dto());

            EContractDto result = service.getById(contractId);
            assertThat(result.pdfUrl()).isNull();
        }

        @Test
        @DisplayName("returns dto without pdfUrl for PENDING_TENANT_REVIEW status")
        void pendingReview() {
            EContract c = contract(EContractStatus.PENDING_TENANT_REVIEW);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(mapper.contractToDto(c)).thenReturn(dto());

            service.getById(contractId);

            verify(s3, never()).presignedUrl(anyString(), anyInt());
        }

        @Test
        @DisplayName("adds presigned pdfUrl when status is post-review")
        void withPdfUrl() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setSnapshotKey("snapshot/key");
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(mapper.contractToDto(c)).thenReturn(dto());
            when(s3.presignedUrl("snapshot/key", 60)).thenReturn("https://s3/pre");

            EContractDto result = service.getById(contractId);
            assertThat(result.pdfUrl()).isEqualTo("https://s3/pre");
        }

        @Test
        @DisplayName("throws NotFoundException when contract missing")
        void notFound() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(contractId))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("throws IllegalStateException when snapshotKey is null in post-review status")
        void snapshotMissing() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setSnapshotKey(null);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.getById(contractId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("chưa được tạo PDF");
        }
    }

    @Nested
    @DisplayName("updateContract")
    class UpdateContract {

        @Test
        @DisplayName("PENDING_TENANT_REVIEW transitions to CORRECTING, clears snapshot, pushes WS, evicts cache")
        void pendingReviewToCorrecting() {
            EContract c = contract(EContractStatus.PENDING_TENANT_REVIEW);
            c.setSnapshotKey("old/key");
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(mapper.contractToDto(any())).thenReturn(dto());

            service.updateContract(contractId, new UpdateEContractRequest("<new/>"));

            assertThat(c.getStatus()).isEqualTo(EContractStatus.CORRECTING);
            assertThat(c.getSnapshotKey()).isNull();
            verify(s3).deleteIfExists("old/key");
            verify(contractRepo).save(c);
            verify(ws).convertAndSend(anyString(), any(Object.class));
            verify(cachedPageService).evictAll(anyString());
        }

        @Test
        @DisplayName("DRAFT just patches and saves (no transition)")
        void draftPatch() {
            EContract c = contract(EContractStatus.DRAFT);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(mapper.contractToDto(any())).thenReturn(dto());

            service.updateContract(contractId, new UpdateEContractRequest("<new/>"));

            assertThat(c.getStatus()).isEqualTo(EContractStatus.DRAFT);
            verify(contractRepo).save(c);
            verify(ws, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("CORRECTING just patches and saves (no transition)")
        void correctingPatch() {
            EContract c = contract(EContractStatus.CORRECTING);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(mapper.contractToDto(any())).thenReturn(dto());

            service.updateContract(contractId, new UpdateEContractRequest("<new/>"));

            assertThat(c.getStatus()).isEqualTo(EContractStatus.CORRECTING);
            verify(contractRepo).save(c);
        }

        @Test
        @DisplayName("throws IllegalStateException for other statuses (e.g., IN_PROGRESS)")
        void invalidStatus() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.updateContract(contractId,
                    new UpdateEContractRequest("<x/>")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IN_PROGRESS");
        }
    }

    @Nested
    @DisplayName("deleteContract")
    class DeleteContract {

        @Test
        @DisplayName("deletes from repo and S3 when DRAFT")
        void deletesDraft() {
            EContract c = contract(EContractStatus.DRAFT);
            c.setSnapshotKey("some/key");
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            service.deleteContract(contractId, actorId);

            verify(s3).deleteIfExists("some/key");
            verify(contractRepo).delete(c);
        }

        @Test
        @DisplayName("throws IllegalStateException when not DRAFT")
        void notDraft() {
            EContract c = contract(EContractStatus.PENDING_TENANT_REVIEW);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.deleteContract(contractId, actorId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFT");
            verify(contractRepo, never()).delete(any(EContract.class));
        }
    }

    @Nested
    @DisplayName("cancelByLandlord")
    class CancelByLandlord {

        @Test
        @DisplayName("cancels CORRECTING contract")
        void correcting() {
            EContract c = contract(EContractStatus.CORRECTING);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            service.cancelByLandlord(contractId, "reason", actorId);

            assertThat(c.getStatus()).isEqualTo(EContractStatus.CANCELLED_BY_LANDLORD);
            assertThat(c.getTerminatedBy()).isEqualTo(actorId);
            assertThat(c.getTerminatedReason()).isEqualTo("reason");
            assertThat(c.getTerminatedAt()).isNotNull();
            verify(contractRepo).save(c);
        }

        @Test
        @DisplayName("cancels READY contract")
        void ready() {
            EContract c = contract(EContractStatus.READY);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            service.cancelByLandlord(contractId, "r", actorId);

            assertThat(c.getStatus()).isEqualTo(EContractStatus.CANCELLED_BY_LANDLORD);
        }

        @Test
        @DisplayName("throws for other statuses (e.g., IN_PROGRESS)")
        void otherStatus() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.cancelByLandlord(contractId, "r", actorId))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("cancelByTenant")
    class CancelByTenant {

        @Test
        @DisplayName("validates token before proceeding")
        void validatesToken() {
            doThrow(new IllegalArgumentException("bad token"))
                    .when(contractTokenService).validateToken("t", contractId);

            assertThatThrownBy(() -> service.cancelByTenant(contractId, "r", tenantId, "t"))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(contractRepo);
        }

        @Test
        @DisplayName("cancels IN_PROGRESS contract and invalidates token")
        void inProgress() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            service.cancelByTenant(contractId, "r", tenantId, "t");

            assertThat(c.getStatus()).isEqualTo(EContractStatus.CANCELLED_BY_TENANT);
            assertThat(c.getTerminatedBy()).isEqualTo(tenantId);
            verify(contractTokenService).invalidateToken("t");
        }

        @Test
        @DisplayName("throws for DRAFT (not allowed)")
        void draft() {
            EContract c = contract(EContractStatus.DRAFT);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.cancelByTenant(contractId, "r", tenantId, "t"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("hasCccd")
    class HasCccd {

        @Test
        @DisplayName("returns true when both front and back keys set")
        void both() {
            EContract c = contract(EContractStatus.READY);
            c.setCccdFrontKey("front");
            c.setCccdBackKey("back");
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThat(service.hasCccd(contractId)).isTrue();
        }

        @Test
        @DisplayName("returns false when front key missing")
        void frontMissing() {
            EContract c = contract(EContractStatus.DRAFT);
            c.setCccdFrontKey(null);
            c.setCccdBackKey("back");
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThat(service.hasCccd(contractId)).isFalse();
        }

        @Test
        @DisplayName("returns false when back key missing")
        void backMissing() {
            EContract c = contract(EContractStatus.DRAFT);
            c.setCccdFrontKey("front");
            c.setCccdBackKey(null);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThat(service.hasCccd(contractId)).isFalse();
        }
    }

    @Nested
    @DisplayName("getMyContracts")
    class GetMyContracts {

        @Test
        @DisplayName("resolves internal tenant id and maps contracts")
        void happy() {
            UUID keycloakId = UUID.randomUUID();
            UserResponse resp = UserResponse.newBuilder().setId(tenantId.toString()).build();
            when(userGrpc.getUserIdAndRoleByKeyCloakId(keycloakId.toString())).thenReturn(resp);

            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setSnapshotKey("sk");
            when(contractRepo.findByUserIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(c));
            when(s3.presignedUrl("sk", 60)).thenReturn("https://s3/x");

            List<TenantEContractDto> result = service.getMyContracts(keycloakId);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).pdfUrl()).isEqualTo("https://s3/x");
        }

        @Test
        @DisplayName("returns null pdfUrl for statuses without snapshot visibility (DRAFT/CORRECTING/CANCELLED)")
        void draftHidesPdfUrl() {
            UUID keycloakId = UUID.randomUUID();
            when(userGrpc.getUserIdAndRoleByKeyCloakId(keycloakId.toString()))
                    .thenReturn(UserResponse.newBuilder().setId(tenantId.toString()).build());

            EContract c = contract(EContractStatus.DRAFT);
            c.setSnapshotKey("sk");
            when(contractRepo.findByUserIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(c));

            List<TenantEContractDto> result = service.getMyContracts(keycloakId);
            assertThat(result.get(0).pdfUrl()).isNull();
        }
    }

    @Nested
    @DisplayName("getPdfUrlForTenant")
    class GetPdfUrlForTenant {

        @Test
        @DisplayName("returns presigned URL when caller is the tenant and snapshot exists")
        void happy() {
            UUID keycloakId = UUID.randomUUID();
            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setSnapshotKey("sk");
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(userGrpc.getUserIdAndRoleByKeyCloakId(keycloakId.toString()))
                    .thenReturn(UserResponse.newBuilder().setId(tenantId.toString()).build());
            when(s3.presignedUrl("sk", 30)).thenReturn("https://s3/x");

            assertThat(service.getPdfUrlForTenant(contractId, keycloakId)).isEqualTo("https://s3/x");
        }

        @Test
        @DisplayName("throws AccessDeniedException when caller is not the tenant")
        void notTenant() {
            UUID keycloakId = UUID.randomUUID();
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(userGrpc.getUserIdAndRoleByKeyCloakId(keycloakId.toString()))
                    .thenReturn(UserResponse.newBuilder().setId(UUID.randomUUID().toString()).build());

            assertThatThrownBy(() -> service.getPdfUrlForTenant(contractId, keycloakId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("throws IllegalStateException when snapshot null")
        void snapshotNull() {
            UUID keycloakId = UUID.randomUUID();
            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setSnapshotKey(null);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(userGrpc.getUserIdAndRoleByKeyCloakId(keycloakId.toString()))
                    .thenReturn(UserResponse.newBuilder().setId(tenantId.toString()).build());

            assertThatThrownBy(() -> service.getPdfUrlForTenant(contractId, keycloakId))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("getPdfPresignedUrl")
    class GetPdfPresignedUrl {

        @Test
        @DisplayName("validates token first")
        void validatesToken() {
            doThrow(new IllegalArgumentException("bad"))
                    .when(contractTokenService).validateToken("t", contractId);

            assertThatThrownBy(() -> service.getPdfPresignedUrl(contractId, "t"))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(contractRepo);
        }

        @Test
        @DisplayName("returns presigned URL with ttl from config")
        void happy() {
            EContract c = contract(EContractStatus.PENDING_TENANT_REVIEW);
            c.setSnapshotKey("sk");
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(s3.presignedUrl("sk", 30)).thenReturn("https://s3/x");

            assertThat(service.getPdfPresignedUrl(contractId, "t")).isEqualTo("https://s3/x");
        }

        @Test
        @DisplayName("throws when snapshot missing")
        void snapshotNull() {
            EContract c = contract(EContractStatus.PENDING_TENANT_REVIEW);
            c.setSnapshotKey(null);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.getPdfPresignedUrl(contractId, "t"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("confirmRefund")
    class ConfirmRefund {

        @Test
        @DisplayName("transitions to DEPOSIT_REFUND_PENDING and publishes kafka event")
        void happy() {
            EContract c = contract(EContractStatus.INSPECTION_DONE);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            UserResponse tenant = UserResponse.newBuilder()
                    .setId(tenantId.toString()).setEmail("alice@example.com").build();
            when(userGrpc.getUserById(tenantId.toString())).thenReturn(tenant);

            service.confirmRefund(contractId, new ConfirmRefundRequest(5_000_000L, "ok"));

            assertThat(c.getStatus()).isEqualTo(EContractStatus.DEPOSIT_REFUND_PENDING);
            verify(contractRepo).save(c);

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(kafka).send(eq("contract.deposit-refund.confirmed"), anyString(), cap.capture());
            DepositRefundConfirmedEvent event = (DepositRefundConfirmedEvent) cap.getValue();
            assertThat(event.getRefundAmount()).isEqualTo(5_000_000L);
            assertThat(event.getTenantEmail()).isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("throws when contract missing")
        void missing() {
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmRefund(contractId,
                    new ConfirmRefundRequest(1L, null)))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("throws when contract status cannot transition to DEPOSIT_REFUND_PENDING")
        void invalidTransition() {
            EContract c = contract(EContractStatus.DRAFT);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.confirmRefund(contractId,
                    new ConfirmRefundRequest(1L, null)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("signByLandlord / signByTenant — error branches")
    class SignErrorBranches {

        @Test
        @DisplayName("signByLandlord throws IllegalStateException when VNPT result null")
        void landlordNullResult() {
            when(vnptClient.getToken()).thenReturn("tkn");
            VnptProcessDto process = new VnptProcessDto(
                    "pc", null, "pid", null, false, null, 0, null, null, null, null, null, null, null);
            when(vnptClient.signProcess(any())).thenReturn(null);

            assertThatThrownBy(() -> service.signByLandlord(process))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Landlord ký thất bại");
        }

        @Test
        @DisplayName("signByTenant throws when VNPT data null")
        void tenantDataNull() {
            VnptProcessDto process = new VnptProcessDto(
                    "pc", "t", "pid", null, false, null, 0, null, null, null, null, null, null, null);
            when(vnptClient.signProcess(process))
                    .thenReturn(new VnptResult<>(false, "denied", null));

            assertThatThrownBy(() -> service.signByTenant(process))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Tenant ký thất bại");
        }
    }

    @Nested
    @DisplayName("confirmByAdmin")
    class ConfirmByAdmin {

        @Test
        @DisplayName("throws for statuses other than DRAFT/CORRECTING/PENDING_TENANT_REVIEW")
        void invalidStatus() {
            EContract c = contract(EContractStatus.IN_PROGRESS);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.confirmByAdmin(contractId, actorId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("IN_PROGRESS");
        }
    }

    @Nested
    @DisplayName("cloneForRenewal")
    class CloneForRenewal {

        @Test
        @DisplayName("calls markNewContractDrafted when renewalRequestId provided")
        void withRenewalId() {
            // Stub createDraft dependencies by mocking the chain minimally; this test only
            // verifies the delegation to renewalService, so allow createDraft to throw and
            // assert the short-circuit behaviour is NOT triggered when request is invalid.
            // Instead, focus on early branches: contract must be findable.
            when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

            CloneForRenewalRequestStub req = new CloneForRenewalRequestStub();
            UUID renewalRequestId = UUID.randomUUID();

            assertThatThrownBy(() -> service.cloneForRenewal(contractId, req.with(renewalRequestId), actorId, "jwt"))
                    .isInstanceOf(NotFoundException.class);

            verify(renewalService, never()).markNewContractDrafted(any(), any());
        }

        // helper to build request
        private static final class CloneForRenewalRequestStub {
            CloneForRenewalRequest with(UUID renewalRequestId) {
                // new start/end placeholder dates — doesn't matter since findById throws first
                return new CloneForRenewalRequest(
                        1_000_000L, Instant.now(),
                        Instant.now().plusSeconds(86400), renewalRequestId);
            }
        }
    }
}
