package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.ContractRelocationRequestDto;
import com.isums.contractservice.domains.dtos.CreateLandlordFaultRelocationRequest;
import com.isums.contractservice.domains.dtos.CreateRelocationRequest;
import com.isums.contractservice.domains.dtos.EContractDto;
import com.isums.contractservice.domains.dtos.ReviewRelocationRequest;
import com.isums.contractservice.domains.entities.ContractRelocationRequest;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.DepositHandling;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.RelocationFaultParty;
import com.isums.contractservice.domains.enums.RelocationRequestKind;
import com.isums.contractservice.domains.enums.RelocationRequestStatus;
import com.isums.contractservice.domains.enums.RelocationResolutionType;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.domains.dtos.DepositBookableHouseDto;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.infrastructures.abstracts.LegalTemplateService;
import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.ContractCoTenantRepository;
import com.isums.contractservice.infrastructures.repositories.ContractRelocationRequestRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.houseservice.grpc.HouseResponse;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContractRelocationServiceImpl}. Covers:
 * <ul>
 *   <li>Happy paths for all 10 public methods.</li>
 *   <li>Authorisation: tenant-ownership, region authz for managers, landlord bypass.</li>
 *   <li>State guards: precondition status checks.</li>
 *   <li>Bug-fix invariants: B3 (active lease -> PENDING_REPLACEMENT_HANDOVER),
 *       B14 (rentAmount null pre-validation), B16 (REFUND_TO_TENANT rejected
 *       in REPLACE_HOUSE), and the cancel/confirm-handover endpoints.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContractRelocationServiceImpl")
class ContractRelocationServiceImplTest {

    @Mock private EContractRepository contractRepo;
    @Mock private ContractRelocationRequestRepository relocationRepo;
    @Mock private ContractCoTenantRepository coTenantRepo;
    @Mock private EContractService eContractService;
    @Mock private HouseGrpcClient houseGrpc;
    @Mock private UserGrpcClient userGrpc;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private S3Service s3Service;
    @Mock private LegalTemplateService legalTemplateService;
    @Mock private RenewalRequestRepository renewalRequestRepo;

    @InjectMocks private ContractRelocationServiceImpl service;

    // ----- common test fixtures -----
    private UUID kcId;          // keycloak id (= JWT subject)
    private UUID internalUserId; // internal user id from user-service
    private UUID contractId;
    private UUID houseIdOld;
    private UUID houseIdNew;

    @BeforeEach
    void setUp() {
        kcId = UUID.randomUUID();
        internalUserId = UUID.randomUUID();
        contractId = UUID.randomUUID();
        houseIdOld = UUID.randomUUID();
        houseIdNew = UUID.randomUUID();
    }

    private void stubResolveInternal(UUID kc, UUID internal) {
        UserResponse user = UserResponse.newBuilder().setId(internal.toString()).build();
        lenient().when(userGrpc.getUserIdAndRoleByKeyCloakId(kc.toString())).thenReturn(user);
    }

    private EContract signedContract(UUID userId, UUID houseId, EContractStatus status, Long deposit) {
        EContract c = EContract.builder()
                .id(contractId)
                .userId(userId)
                .houseId(houseId)
                .status(status)
                .depositAmount(deposit)
                .depositStatus(deposit != null && deposit > 0 ? DepositStatus.PAID : DepositStatus.UNPAID)
                .startAt(Instant.now().minus(60, ChronoUnit.DAYS))
                .endAt(Instant.now().plus(300, ChronoUnit.DAYS))
                .rentAmount(5_000_000L)
                .tenantName("Alice")
                .createdBy(UUID.randomUUID())
                .build();
        return c;
    }

    private ContractRelocationRequest reloRow(
            RelocationRequestStatus status,
            RelocationRequestKind kind,
            RelocationFaultParty party,
            UUID tenantInternal,
            UUID approvedHouseId) {
        return ContractRelocationRequest.builder()
                .id(UUID.randomUUID())
                .oldContractId(contractId)
                .tenantId(tenantInternal)
                .oldHouseId(houseIdOld)
                .requestedHouseId(houseIdNew)
                .approvedHouseId(approvedHouseId)
                .status(status)
                .requestKind(kind)
                .faultParty(party)
                .resolutionType(RelocationResolutionType.REPLACE_HOUSE)
                .depositStatusSnapshot(DepositStatus.PAID)
                .depositAmount(10_000_000L)
                .transferredDepositAmount(0L)
                .forfeitAmount(0L)
                .additionalDepositAmount(0L)
                .refundAmount(0L)
                .oldRentProratedAmount(0L)
                .oldUtilitiesAmount(0L)
                .oldDamageAmount(0L)
                .adminFeeAmount(0L)
                .settlementAmount(0L)
                .refundableDepositAmount(0L)
                .totalAdditionalPaymentAmount(0L)
                .requestedBy(tenantInternal)
                .requestedAt(Instant.now())
                .build();
    }

    // ============================================================
    // submit()
    // ============================================================
    @Nested
    @DisplayName("submit (tenant)")
    class Submit {

        @Test
        @DisplayName("happy: creates REQUESTED row with correct kind + legal basis snapshot")
        void happy() {
            stubResolveInternal(kcId, internalUserId);
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(relocationRepo.existsByOldContractIdAndStatusIn(eq(contractId), anyCollection()))
                    .thenReturn(false);
            when(houseGrpc.getHouseById(houseIdNew)).thenReturn(HouseResponse.newBuilder().setId(houseIdNew.toString()).build());
            when(legalTemplateService.resolveSnapshot(anyString(), any())).thenReturn("LEGAL TEXT");
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            CreateRelocationRequest req = new CreateRelocationRequest(
                    houseIdNew, "I want a smaller room", null, null, 2);

            ContractRelocationRequestDto dto = service.submit(contractId, kcId, req);

            ArgumentCaptor<ContractRelocationRequest> cap = ArgumentCaptor.forClass(ContractRelocationRequest.class);
            verify(relocationRepo).save(cap.capture());
            ContractRelocationRequest saved = cap.getValue();
            assertThat(saved.getStatus()).isEqualTo(RelocationRequestStatus.REQUESTED);
            assertThat(saved.getFaultParty()).isEqualTo(RelocationFaultParty.TENANT);
            assertThat(saved.getRequestedBy()).isEqualTo(internalUserId); // B9: internal id
            assertThat(saved.getTenantId()).isEqualTo(internalUserId);
            assertThat(saved.getLegalBasis()).isEqualTo("LEGAL TEXT"); // active-lease snapshot
            assertThat(dto).isNotNull();
        }

        @Test
        @DisplayName("rejects when contract belongs to another tenant")
        void notOwn() {
            stubResolveInternal(kcId, internalUserId);
            UUID otherTenant = UUID.randomUUID();
            EContract c = signedContract(otherTenant, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.submit(contractId, kcId,
                    new CreateRelocationRequest(houseIdNew, "x", null, null, 1)))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("rejects when contract is not COMPLETED (not yet signed)")
        void notSigned() {
            stubResolveInternal(kcId, internalUserId);
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.IN_PROGRESS, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.submit(contractId, kcId,
                    new CreateRelocationRequest(houseIdNew, "x", null, null, 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("after the contract is signed");
        }

        @Test
        @DisplayName("rejects when requested house equals current house")
        void sameHouse() {
            stubResolveInternal(kcId, internalUserId);
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.submit(contractId, kcId,
                    new CreateRelocationRequest(houseIdOld, "x", null, null, 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("different from the current");
        }

        @Test
        @DisplayName("rejects when an open relocation request already exists for this contract")
        void duplicateOpen() {
            stubResolveInternal(kcId, internalUserId);
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(relocationRepo.existsByOldContractIdAndStatusIn(eq(contractId), anyCollection()))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.submit(contractId, kcId,
                    new CreateRelocationRequest(houseIdNew, "x", null, null, 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already has an open relocation request");
        }

        @Test
        @DisplayName("404 when requestedHouseId not found in house service")
        void houseNotFound() {
            stubResolveInternal(kcId, internalUserId);
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(relocationRepo.existsByOldContractIdAndStatusIn(eq(contractId), anyCollection()))
                    .thenReturn(false);
            when(houseGrpc.getHouseById(houseIdNew)).thenReturn(null);

            assertThatThrownBy(() -> service.submit(contractId, kcId,
                    new CreateRelocationRequest(houseIdNew, "x", null, null, 1)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // ============================================================
    // reportLandlordFaultByContractNumber()
    // ============================================================
    @Nested
    @DisplayName("reportLandlordFaultByContractNumber (staff/manager)")
    class ReportLandlordFault {

        @Test
        @DisplayName("rejects when no evidence files attached")
        void noEvidence() {
            // No userGrpc / houseGrpc stubs needed Ă¢â‚¬â€ fails fast on evidence check
            CreateLandlordFaultRelocationRequest req = new CreateLandlordFaultRelocationRequest(
                    null, "house is unsafe", null);

            assertThatThrownBy(() -> service.reportLandlordFaultByContractNumber(
                    "DOC-001", kcId, false, req, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidence");
        }

        @Test
        @DisplayName("happy: LANDLORD bypasses region check, snapshots LANDLORD_FAULT legal basis")
        void landlordBypassRegion() {
            EContract c = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findByDocumentNoIgnoreCase("DOC-001")).thenReturn(Optional.of(c));
            when(relocationRepo.existsByOldContractIdAndStatusIn(eq(contractId), anyCollection())).thenReturn(false);
            stubResolveInternal(kcId, internalUserId);
            // LANDLORD: assertCanActOnHouse should NOT call houseGrpc.getManagedHouseIds
            when(houseGrpc.getHouseById(houseIdNew))
                    .thenReturn(HouseResponse.newBuilder().setId(houseIdNew.toString()).build());
            when(s3Service.uploadRelocationEvidence(any(), eq(contractId), eq(kcId)))
                    .thenReturn("relocation-evidence/" + contractId + "/img1.jpg");
            org.mockito.Mockito.lenient()
                    .when(s3Service.presignedUrl(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(i -> "https://s3.signed/" + i.getArgument(0));
            when(legalTemplateService.resolveSnapshot(anyString(), any())).thenReturn("LANDLORD-FAULT TEXT");
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            MultipartFile mf = stubFile();
            CreateLandlordFaultRelocationRequest req = new CreateLandlordFaultRelocationRequest(
                    houseIdNew, "leak in roof", null);

            service.reportLandlordFaultByContractNumber("DOC-001", kcId, true, req, List.of(mf));

            // landlord=true skipped getManagedHouseIds
            verify(houseGrpc, never()).getManagedHouseIds(any());
            ArgumentCaptor<ContractRelocationRequest> cap = ArgumentCaptor.forClass(ContractRelocationRequest.class);
            verify(relocationRepo).save(cap.capture());
            assertThat(cap.getValue().getFaultParty()).isEqualTo(RelocationFaultParty.LANDLORD);
            assertThat(cap.getValue().getRequestKind()).isEqualTo(RelocationRequestKind.LANDLORD_FAULT_UNINHABITABLE);
            assertThat(cap.getValue().getLegalBasis()).isEqualTo("LANDLORD-FAULT TEXT");
        }

        @Test
        @DisplayName("manager (non-landlord) blocked when houseGrpc says they don't manage it")
        void managerCrossRegionBlocked() {
            EContract c = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findByDocumentNoIgnoreCase("DOC-001")).thenReturn(Optional.of(c));
            when(relocationRepo.existsByOldContractIdAndStatusIn(eq(contractId), anyCollection())).thenReturn(false);
            stubResolveInternal(kcId, internalUserId);
            when(houseGrpc.getManagedHouseIds(internalUserId)).thenReturn(Set.of(UUID.randomUUID())); // manages OTHER

            MultipartFile mf = stubFile();
            CreateLandlordFaultRelocationRequest req = new CreateLandlordFaultRelocationRequest(
                    null, "issue", null);

            assertThatThrownBy(() -> service.reportLandlordFaultByContractNumber(
                    "DOC-001", kcId, false, req, List.of(mf)))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("rejects when reportReason is blank")
        void blankReason() {
            EContract c = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findByDocumentNoIgnoreCase("DOC-001")).thenReturn(Optional.of(c));

            CreateLandlordFaultRelocationRequest req = new CreateLandlordFaultRelocationRequest(
                    null, "  ", null);

            assertThatThrownBy(() -> service.reportLandlordFaultByContractNumber(
                    "DOC-001", kcId, true, req, List.of(stubFile())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Report reason is required");
        }

        @Test
        @DisplayName("rejects when reportReason exceeds 1000 chars")
        void longReason() {
            EContract c = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findByDocumentNoIgnoreCase("DOC-001")).thenReturn(Optional.of(c));

            CreateLandlordFaultRelocationRequest req = new CreateLandlordFaultRelocationRequest(
                    null, "x".repeat(1001), null);

            assertThatThrownBy(() -> service.reportLandlordFaultByContractNumber(
                    "DOC-001", kcId, true, req, List.of(stubFile())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1000 characters");
        }

        @Test
        @DisplayName("rejects when contract not yet signed (status != COMPLETED)")
        void contractNotSigned() {
            EContract c = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.IN_PROGRESS, 10_000_000L);
            when(contractRepo.findByDocumentNoIgnoreCase("DOC-001")).thenReturn(Optional.of(c));

            CreateLandlordFaultRelocationRequest req = new CreateLandlordFaultRelocationRequest(
                    null, "leak", null);
            assertThatThrownBy(() -> service.reportLandlordFaultByContractNumber(
                    "DOC-001", kcId, true, req, List.of(stubFile())))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ============================================================
    // review() Ă¢â‚¬â€ REJECTED branch
    // ============================================================
    @Nested
    @DisplayName("review (manager)")
    class Review {

        @Test
        @DisplayName("rejected request: status -> REJECTED, no contract status change")
        void rejectPath() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            ReviewRelocationRequest req = reviewBuilder(false).build();
            service.review(r.getId(), kcId, true, req); // landlord bypass

            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.REJECTED);
            verify(contractRepo, never()).save(any());
        }

        @Test
        @DisplayName("rejects when status != REQUESTED")
        void wrongStatus() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.review(r.getId(), kcId, true,
                    reviewBuilder(true).approvedHouseId(houseIdNew).build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only REQUESTED");
        }

        @Test
        @DisplayName("approve REPLACE_HOUSE for pre-handover -> status APPROVED")
        void approveReplaceHouse() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(houseGrpc.getHouseById(houseIdNew)).thenReturn(HouseResponse.newBuilder().setId(houseIdNew.toString()).build());
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            ReviewRelocationRequest req = reviewBuilder(true)
                    .resolutionType(RelocationResolutionType.REPLACE_HOUSE)
                    .approvedHouseId(houseIdNew)
                    .newRentAmount(5_500_000L)
                    .newDepositAmount(11_000_000L)
                    .build();

            service.review(r.getId(), kcId, true, req);

            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.APPROVED);
            assertThat(r.getApprovedHouseId()).isEqualTo(houseIdNew);
            assertThat(r.getNewRentAmount()).isEqualTo(5_500_000L);
        }

        @Test
        @DisplayName("approve REPLACE_HOUSE for active-lease -> status QUOTED (waiting tenant accept)")
        void approveActiveLease() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(houseGrpc.getHouseById(houseIdNew)).thenReturn(HouseResponse.newBuilder().setId(houseIdNew.toString()).build());
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            ReviewRelocationRequest req = reviewBuilder(true)
                    .resolutionType(RelocationResolutionType.REPLACE_HOUSE)
                    .approvedHouseId(houseIdNew)
                    .newRentAmount(6_000_000L)
                    .newDepositAmount(12_000_000L)
                    .build();

            service.review(r.getId(), kcId, true, req);

            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.QUOTED);
        }

        @Test
        @DisplayName("REFUND_TO_TENANT handling rejected in REPLACE_HOUSE flow (B16)")
        void refundToTenantRejected() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            EContract c = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(houseGrpc.getHouseById(houseIdNew)).thenReturn(HouseResponse.newBuilder().setId(houseIdNew.toString()).build());
            stubResolveInternal(kcId, internalUserId);

            ReviewRelocationRequest req = reviewBuilder(true)
                    .resolutionType(RelocationResolutionType.REPLACE_HOUSE)
                    .depositHandling(DepositHandling.REFUND_TO_TENANT)
                    .approvedHouseId(houseIdNew)
                    .newRentAmount(5_000_000L)
                    .newDepositAmount(10_000_000L)
                    .build();

            assertThatThrownBy(() -> service.review(r.getId(), kcId, true, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("REFUND_TERMINATE");
        }

        @Test
        @DisplayName("manager-region check: blocked when manager doesn't manage old or approved house")
        void managerCrossRegionBlocked() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            when(houseGrpc.getManagedHouseIds(internalUserId)).thenReturn(Set.of(UUID.randomUUID())); // wrong region

            ReviewRelocationRequest req = reviewBuilder(true)
                    .approvedHouseId(houseIdNew)
                    .build();

            assertThatThrownBy(() -> service.review(r.getId(), kcId, false, req))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ============================================================
    // acceptQuote()
    // ============================================================
    @Nested
    @DisplayName("acceptQuote (tenant)")
    class AcceptQuote {

        @Test
        @DisplayName("happy: QUOTED -> APPROVED, sets tenantAcceptedAt")
        void happy() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.QUOTED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            service.acceptQuote(r.getId(), kcId);

            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.APPROVED);
            assertThat(r.getTenantAcceptedAt()).isNotNull();
        }

        @Test
        @DisplayName("idempotent: already APPROVED returns same DTO without throwing")
        void idempotent() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            assertThatCode(() -> service.acceptQuote(r.getId(), kcId)).doesNotThrowAnyException();
            verify(relocationRepo, never()).save(any());
        }

        @Test
        @DisplayName("rejects: not own relocation")
        void notOwn() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.QUOTED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, UUID.randomUUID(), houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            assertThatThrownBy(() -> service.acceptQuote(r.getId(), kcId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("rejects: kind != ACTIVE_LEASE_TENANT_UPGRADE")
        void wrongKind() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.QUOTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            assertThatThrownBy(() -> service.acceptQuote(r.getId(), kcId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active lease");
        }

        @Test
        @DisplayName("rejects: status != QUOTED (e.g. REQUESTED)")
        void wrongStatus() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            assertThatThrownBy(() -> service.acceptQuote(r.getId(), kcId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only QUOTED");
        }
    }

    // ============================================================
    // createReplacementContract()
    // ============================================================
    @Nested
    @DisplayName("createReplacementContract")
    class CreateReplacement {

        @Test
        @DisplayName("rejects: status != APPROVED")
        void wrongStatus() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.createReplacementContract(r.getId(), kcId, true, "jwt"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("after approval");
        }

        @Test
        @DisplayName("rejects: REFUND_TERMINATE requests don't get replacement contract")
        void refundTerminateRejected() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.LANDLORD_FAULT_UNINHABITABLE,
                    RelocationFaultParty.LANDLORD, internalUserId, null);
            r.setResolutionType(RelocationResolutionType.REFUND_TERMINATE);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.createReplacementContract(r.getId(), kcId, true, "jwt"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refund termination");
        }

        @Test
        @DisplayName("B14: rejects when newRentAmount missing from review")
        void b14MissingRent() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            r.setNewRentAmount(null);
            r.setNewDepositAmount(11_000_000L);
            r.setNewStartAt(Instant.now());
            r.setNewEndAt(Instant.now().plus(365, ChronoUnit.DAYS));
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            EContract old = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(old));

            assertThatThrownBy(() -> service.createReplacementContract(r.getId(), kcId, true, "jwt"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("new rent amount missing");
        }

        @Test
        @DisplayName("B3: ACTIVE_LEASE -> old contract -> PENDING_REPLACEMENT_HANDOVER (not REPLACED yet)")
        void activeLeasePendingHandover() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            r.setNewRentAmount(6_000_000L);
            r.setNewDepositAmount(12_000_000L);
            r.setNewStartAt(Instant.now().plus(7, ChronoUnit.DAYS));
            r.setNewEndAt(Instant.now().plus(372, ChronoUnit.DAYS));
            r.setNewHandoverDate(Instant.now().plus(7, ChronoUnit.DAYS));
            r.setDepositHandling(DepositHandling.TRANSFER_TO_REPLACEMENT);
            r.setTransferredDepositAmount(10_000_000L);

            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            EContract old = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(old));

            UserResponse tenant = UserResponse.newBuilder()
                    .setId(internalUserId.toString())
                    .setEmail("alice@example.com").build();
            when(userGrpc.getUserById(internalUserId.toString())).thenReturn(tenant);

            UUID newContractId = UUID.randomUUID();
            EContractDto draftDto = mockDto(newContractId);
            when(eContractService.createDraft(eq(kcId), eq("jwt"), any())).thenReturn(draftDto);

            EContract replacement = signedContract(internalUserId, houseIdNew, EContractStatus.DRAFT, null);
            replacement.setId(newContractId);
            when(contractRepo.findById(newContractId)).thenReturn(Optional.of(replacement));
            when(coTenantRepo.findByContractId(contractId)).thenReturn(List.of());
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));
            when(contractRepo.save(any(EContract.class))).thenAnswer(i -> i.getArgument(0));

            service.createReplacementContract(r.getId(), kcId, true, "jwt");

            // Old contract MUST be in PENDING_REPLACEMENT_HANDOVER (B3 invariant)
            assertThat(old.getStatus()).isEqualTo(EContractStatus.PENDING_REPLACEMENT_HANDOVER);
            assertThat(old.getReplacedByContractId()).isEqualTo(newContractId);
            // Relocation status: CONTRACT_CREATED
            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.CONTRACT_CREATED);
            assertThat(r.getNewContractId()).isEqualTo(newContractId);
            verify(outboxPublisher, never()).enqueue(eq("contract.replaced"), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("PRE_HANDOVER -> old contract -> REPLACED_AFTER_DEPOSIT (deposit was paid)")
        void preHandoverReplaced() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            r.setNewRentAmount(6_000_000L);
            r.setNewDepositAmount(12_000_000L);
            r.setNewStartAt(Instant.now());
            r.setNewEndAt(Instant.now().plus(365, ChronoUnit.DAYS));
            r.setNewHandoverDate(Instant.now());
            r.setDepositHandling(DepositHandling.TRANSFER_TO_REPLACEMENT);
            r.setDepositStatusSnapshot(DepositStatus.PAID);

            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            EContract old = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(old));
            UserResponse tenant = UserResponse.newBuilder()
                    .setId(internalUserId.toString()).setEmail("a@b.c").build();
            when(userGrpc.getUserById(internalUserId.toString())).thenReturn(tenant);

            UUID newContractId = UUID.randomUUID();
            EContractDto draftDto = mockDto(newContractId);
            when(eContractService.createDraft(any(), any(), any())).thenReturn(draftDto);
            EContract replacement = signedContract(internalUserId, houseIdNew, EContractStatus.DRAFT, null);
            replacement.setId(newContractId);
            when(contractRepo.findById(newContractId)).thenReturn(Optional.of(replacement));
            when(coTenantRepo.findByContractId(contractId)).thenReturn(List.of());
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));
            when(contractRepo.save(any(EContract.class))).thenAnswer(i -> i.getArgument(0));

            service.createReplacementContract(r.getId(), kcId, true, "jwt");

            // Pre-handover terminates old contract immediately, but house handoff waits
            // until the replacement contract is completed.
            assertThat(old.getStatus()).isEqualTo(EContractStatus.REPLACED_AFTER_DEPOSIT);
            verify(outboxPublisher, never()).enqueue(eq("contract.replaced"), anyString(), any(), anyString());
        }
    }

    // ============================================================
    // cancelByTenant()
    // ============================================================
    @Nested
    @DisplayName("cancelByTenant")
    class CancelByTenant {

        @Test
        @DisplayName("REQUESTED -> CANCELLED")
        void cancelRequested() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            service.cancelByTenant(r.getId(), kcId);

            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.CANCELLED);
            assertThat(r.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("QUOTED -> CANCELLED also allowed")
        void cancelQuoted() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.QUOTED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            service.cancelByTenant(r.getId(), kcId);
            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.CANCELLED);
        }

        @Test
        @DisplayName("rejects when not own relocation")
        void notOwn() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, UUID.randomUUID(), null);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            assertThatThrownBy(() -> service.cancelByTenant(r.getId(), kcId))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("rejects when status is APPROVED (must go through manager)")
        void wrongStatus() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            assertThatThrownBy(() -> service.cancelByTenant(r.getId(), kcId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REQUESTED or QUOTED");
        }
    }

    // ============================================================
    // cancelByManager()
    // ============================================================
    @Nested
    @DisplayName("cancelByManager")
    class CancelByManager {

        @Test
        @DisplayName("APPROVED -> CANCELLED (LANDLORD bypasses region check)")
        void cancelApproved() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));

            service.cancelByManager(r.getId(), kcId, true);

            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.CANCELLED);
            assertThat(r.getReviewedBy()).isEqualTo(internalUserId);
            verify(houseGrpc, never()).getManagedHouseIds(any());
        }

        @Test
        @DisplayName("rejects when status == CONTRACT_CREATED (must cancel new contract instead)")
        void contractAlreadyCreated() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            assertThatThrownBy(() -> service.cancelByManager(r.getId(), kcId, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before contract creation");
        }
    }

    // ============================================================
    // confirmHandover()
    // ============================================================
    @Nested
    @DisplayName("confirmHandover")
    class ConfirmHandover {

        @Test
        @DisplayName("happy: ACTIVE_LEASE + CONTRACT_CREATED + new=COMPLETED + old=PENDING -> done")
        void happy() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            UUID newContractId = UUID.randomUUID();
            r.setNewContractId(newContractId);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            EContract newC = signedContract(internalUserId, houseIdNew, EContractStatus.COMPLETED, null);
            newC.setId(newContractId);
            when(contractRepo.findById(newContractId)).thenReturn(Optional.of(newC));

            EContract oldC = signedContract(internalUserId, houseIdOld, EContractStatus.PENDING_REPLACEMENT_HANDOVER, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(oldC));

            when(relocationRepo.save(any(ContractRelocationRequest.class))).thenAnswer(i -> i.getArgument(0));
            when(contractRepo.save(any(EContract.class))).thenAnswer(i -> i.getArgument(0));

            service.confirmHandover(r.getId(), kcId, true);

            assertThat(oldC.getStatus()).isEqualTo(EContractStatus.REPLACED_AFTER_DEPOSIT);
            assertThat(oldC.getTerminatedAt()).isNotNull();
            assertThat(oldC.getTerminatedBy()).isEqualTo(internalUserId);
            assertThat(r.getStatus()).isEqualTo(RelocationRequestStatus.COMPLETED);
            assertThat(r.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("rejects: kind != ACTIVE_LEASE_TENANT_UPGRADE")
        void wrongKind() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.confirmHandover(r.getId(), kcId, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only applies to active-lease");
        }

        @Test
        @DisplayName("rejects: relocation status != CONTRACT_CREATED")
        void wrongStatus() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.APPROVED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.confirmHandover(r.getId(), kcId, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONTRACT_CREATED");
        }

        @Test
        @DisplayName("rejects: replacement contract not yet COMPLETED (not signed)")
        void newContractNotSigned() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            UUID newContractId = UUID.randomUUID();
            r.setNewContractId(newContractId);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            EContract newC = signedContract(internalUserId, houseIdNew, EContractStatus.IN_PROGRESS, null);
            newC.setId(newContractId);
            when(contractRepo.findById(newContractId)).thenReturn(Optional.of(newC));

            assertThatThrownBy(() -> service.confirmHandover(r.getId(), kcId, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be signed");
        }

        @Test
        @DisplayName("rejects: old contract status != PENDING_REPLACEMENT_HANDOVER")
        void oldNotPending() {
            ContractRelocationRequest r = reloRow(RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, internalUserId, houseIdNew);
            UUID newContractId = UUID.randomUUID();
            r.setNewContractId(newContractId);
            when(relocationRepo.findById(r.getId())).thenReturn(Optional.of(r));
            stubResolveInternal(kcId, internalUserId);

            EContract newC = signedContract(internalUserId, houseIdNew, EContractStatus.COMPLETED, null);
            newC.setId(newContractId);
            when(contractRepo.findById(newContractId)).thenReturn(Optional.of(newC));

            // Old contract is in some weird state (e.g. someone messed with it)
            EContract oldC = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(oldC));

            assertThatThrownBy(() -> service.confirmHandover(r.getId(), kcId, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not awaiting handover");
        }
    }

    // ============================================================
    // getMine() / getAll()
    // ============================================================
    @Nested
    @DisplayName("getMine / getAll")
    class ListsAndQueries {

        @Test
        @DisplayName("getMine resolves keycloak->internal then queries by tenantId")
        void getMine() {
            stubResolveInternal(kcId, internalUserId);
            when(relocationRepo.findByTenantIdOrderByCreatedAtDesc(internalUserId)).thenReturn(List.of());

            service.getMine(kcId);

            verify(relocationRepo).findByTenantIdOrderByCreatedAtDesc(internalUserId);
        }

        @Test
        @DisplayName("getAll: LANDLORD sees everything (no region filter)")
        void getAllLandlord() {
            ContractRelocationRequest r1 = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, UUID.randomUUID(), null);
            ContractRelocationRequest r2 = reloRow(RelocationRequestStatus.QUOTED,
                    RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE,
                    RelocationFaultParty.TENANT, UUID.randomUUID(), houseIdNew);
            when(relocationRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(r1, r2));

            List<ContractRelocationRequestDto> dtos = service.getAll(kcId, true);

            assertThat(dtos).hasSize(2);
            verify(houseGrpc, never()).getManagedHouseIds(any());
        }

        @Test
        @DisplayName("getAll: MANAGER region filter (B1) Ă¢â‚¬â€ only sees their managed houses")
        void getAllManagerFiltered() {
            stubResolveInternal(kcId, internalUserId);
            UUID otherHouse = UUID.randomUUID();
            ContractRelocationRequest mine = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, UUID.randomUUID(), null);
            ContractRelocationRequest other = reloRow(RelocationRequestStatus.REQUESTED,
                    RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST,
                    RelocationFaultParty.TENANT, UUID.randomUUID(), null);
            other.setOldHouseId(otherHouse);
            other.setRequestedHouseId(UUID.randomUUID());

            when(relocationRepo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(mine, other));
            when(houseGrpc.getManagedHouseIds(internalUserId)).thenReturn(Set.of(houseIdOld));

            List<ContractRelocationRequestDto> dtos = service.getAll(kcId, false);

            // Only "mine" (oldHouseId == houseIdOld) should pass; "other" was on different region
            assertThat(dtos).hasSize(1);
        }
    }

    // ----- helpers -----
    private MultipartFile stubFile() {
        MultipartFile mf = org.mockito.Mockito.mock(MultipartFile.class);
        lenient().when(mf.isEmpty()).thenReturn(false);
        return mf;
    }

    private ReviewBuilder reviewBuilder(boolean approved) {
        return new ReviewBuilder().approved(approved);
    }

    private EContractDto mockDto(UUID id) {
        EContractDto dto = org.mockito.Mockito.mock(EContractDto.class);
        lenient().when(dto.id()).thenReturn(id);
        return dto;
    }

    /** Tiny builder around the verbose ReviewRelocationRequest record. */
    private static final class ReviewBuilder {
        private Boolean approved;
        private RelocationResolutionType resolutionType;
        private UUID approvedHouseId;
        private DepositHandling depositHandling;
        private Long newRentAmount, newDepositAmount;
        private Instant newStartAt, newEndAt, newHandoverDate;
        private Long transferredDepositAmount, forfeitAmount, additionalDepositAmount;
        private Long oldRentProratedAmount, oldUtilitiesAmount, oldDamageAmount, adminFeeAmount;
        private Long settlementAmount, refundableDepositAmount, totalAdditionalPaymentAmount;
        private String inspectionNote;
        private Long refundAmount;
        private Instant refundDueAt;
        private String legalBasis, managerNote;

        ReviewBuilder approved(boolean v) { this.approved = v; return this; }
        ReviewBuilder resolutionType(RelocationResolutionType v) { this.resolutionType = v; return this; }
        ReviewBuilder approvedHouseId(UUID v) { this.approvedHouseId = v; return this; }
        ReviewBuilder depositHandling(DepositHandling v) { this.depositHandling = v; return this; }
        ReviewBuilder newRentAmount(Long v) { this.newRentAmount = v; return this; }
        ReviewBuilder newDepositAmount(Long v) { this.newDepositAmount = v; return this; }

        ReviewRelocationRequest build() {
            return new ReviewRelocationRequest(
                    approved, resolutionType, approvedHouseId, depositHandling,
                    newRentAmount, newDepositAmount, newStartAt, newEndAt, newHandoverDate,
                    transferredDepositAmount, forfeitAmount, additionalDepositAmount,
                    oldRentProratedAmount, oldUtilitiesAmount, oldDamageAmount,
                    adminFeeAmount, settlementAmount, refundableDepositAmount,
                    totalAdditionalPaymentAmount, inspectionNote, refundAmount,
                    refundDueAt, legalBasis, managerNote);
        }
    }

    // ============================================================
    // findDepositBookableHouses()
    // ============================================================
    @Nested
    @DisplayName("findDepositBookableHouses (marketplace)")
    class DepositBookable {

        @Test
        @DisplayName("returns empty list when no contracts are expiring")
        void empty() {
            stubResolveInternal(kcId, internalUserId);
            when(contractRepo.findByStatusAndEndAtBetween(
                    eq(EContractStatus.COMPLETED), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of());

            List<DepositBookableHouseDto> dtos = service.findDepositBookableHouses(kcId);

            assertThat(dtos).isEmpty();
        }

        @Test
        @DisplayName("returns expiring contract's house with availableFrom = endAt + buffer")
        void happy() {
            stubResolveInternal(kcId, internalUserId);
            UUID otherTenant = UUID.randomUUID();
            EContract expiring = signedContract(otherTenant, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            // override endAt to simulate "expires in 10 days"
            Instant endAt = Instant.now().plus(10, ChronoUnit.DAYS);
            expiring.setEndAt(endAt);
            expiring.setDepositRefundDays(7);
            when(contractRepo.findByStatusAndEndAtBetween(
                    eq(EContractStatus.COMPLETED), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(expiring));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(
                    eq(expiring.getId()), anyList())).thenReturn(false);
            when(houseGrpc.getHouseById(houseIdOld)).thenReturn(
                    HouseResponse.newBuilder()
                            .setId(houseIdOld.toString())
                            .setName("Phong A2")
                            .setAddress("123 Le Van Sy")
                            .setCity("HCMC")
                            .setCommune("Q3")
                            .setWard("P5")
                            .build());

            List<DepositBookableHouseDto> dtos = service.findDepositBookableHouses(kcId);

            assertThat(dtos).hasSize(1);
            DepositBookableHouseDto d = dtos.get(0);
            assertThat(d.houseId()).isEqualTo(houseIdOld);
            assertThat(d.houseName()).isEqualTo("Phong A2");
            assertThat(d.currentContractEndAt()).isEqualTo(endAt);
            assertThat(d.availableFrom()).isEqualTo(endAt.plus(7, ChronoUnit.DAYS));
        }

        @Test
        @DisplayName("excludes contracts with active renewal request")
        void excludesActiveRenewal() {
            stubResolveInternal(kcId, internalUserId);
            EContract expiring = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            expiring.setEndAt(Instant.now().plus(10, ChronoUnit.DAYS));
            when(contractRepo.findByStatusAndEndAtBetween(
                    eq(EContractStatus.COMPLETED), any(), any()))
                    .thenReturn(List.of(expiring));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(
                    eq(expiring.getId()), anyList())).thenReturn(true);

            List<DepositBookableHouseDto> dtos = service.findDepositBookableHouses(kcId);
            assertThat(dtos).isEmpty();
        }

        @Test
        @DisplayName("excludes contracts with active relocation in flight")
        void excludesActiveRelocation() {
            stubResolveInternal(kcId, internalUserId);
            EContract expiring = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            expiring.setEndAt(Instant.now().plus(10, ChronoUnit.DAYS));
            when(contractRepo.findByStatusAndEndAtBetween(
                    eq(EContractStatus.COMPLETED), any(), any()))
                    .thenReturn(List.of(expiring));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(any(), anyList()))
                    .thenReturn(false);
            when(relocationRepo.existsByOldContractIdAndStatusIn(
                    eq(expiring.getId()), anyCollection())).thenReturn(true);

            List<DepositBookableHouseDto> dtos = service.findDepositBookableHouses(kcId);
            assertThat(dtos).isEmpty();
        }

        @Test
        @DisplayName("excludes caller's own contracts (don't show me my own house)")
        void excludesCallerOwnContracts() {
            stubResolveInternal(kcId, internalUserId);
            EContract myContract = signedContract(internalUserId, houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            myContract.setEndAt(Instant.now().plus(10, ChronoUnit.DAYS));
            when(contractRepo.findByStatusAndEndAtBetween(
                    eq(EContractStatus.COMPLETED), any(), any()))
                    .thenReturn(List.of(myContract));

            List<DepositBookableHouseDto> dtos = service.findDepositBookableHouses(kcId);

            assertThat(dtos).isEmpty();
            // We should NOT have queried renewal/relocation repos because we filter on userId first
            verify(renewalRequestRepo, never()).existsByContractIdAndStatusNotIn(any(), anyList());
        }

        @Test
        @DisplayName("uses default 7-day handover buffer when contract has no depositRefundDays")
        void defaultBuffer() {
            stubResolveInternal(kcId, internalUserId);
            EContract expiring = signedContract(UUID.randomUUID(), houseIdOld, EContractStatus.COMPLETED, 10_000_000L);
            Instant endAt = Instant.now().plus(15, ChronoUnit.DAYS);
            expiring.setEndAt(endAt);
            expiring.setDepositRefundDays(null); // no value set
            when(contractRepo.findByStatusAndEndAtBetween(
                    eq(EContractStatus.COMPLETED), any(), any()))
                    .thenReturn(List.of(expiring));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(any(), anyList())).thenReturn(false);
            when(houseGrpc.getHouseById(houseIdOld)).thenReturn(
                    HouseResponse.newBuilder().setId(houseIdOld.toString()).setName("X").setAddress("Y").build());

            List<DepositBookableHouseDto> dtos = service.findDepositBookableHouses(kcId);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).availableFrom()).isEqualTo(endAt.plus(7, ChronoUnit.DAYS));
        }
    }

}
