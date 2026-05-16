package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CoTenantDto;
import com.isums.contractservice.domains.dtos.ContractRelocationRequestDto;
import com.isums.contractservice.domains.dtos.CreateEContractRequest;
import com.isums.contractservice.domains.dtos.CreateLandlordFaultRelocationRequest;
import com.isums.contractservice.domains.dtos.CreateRelocationRequest;
import com.isums.contractservice.domains.dtos.DepositBookableHouseDto;
import com.isums.contractservice.domains.dtos.EContractDto;
import com.isums.contractservice.domains.dtos.ReviewRelocationRequest;
import com.isums.contractservice.domains.entities.ContractCoTenant;
import com.isums.contractservice.domains.entities.ContractRelocationRequest;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.DepositHandling;
import com.isums.contractservice.domains.enums.DepositStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.LegalTemplateKey;
import com.isums.contractservice.domains.enums.RelocationFaultParty;
import com.isums.contractservice.domains.enums.RelocationRequestKind;
import com.isums.contractservice.domains.enums.RelocationRequestStatus;
import com.isums.contractservice.domains.enums.RelocationResolutionType;
import com.isums.contractservice.domains.enums.RelocationStateMachine;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import com.isums.contractservice.domains.events.CancelDepositInvoiceRequestedEvent;
import com.isums.contractservice.domains.events.ContractReplacedEvent;
import com.isums.contractservice.domains.events.DepositRefundConfirmedEvent;
import com.isums.contractservice.domains.events.RelocationReportedEvent;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.ContractRelocationService;
import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.infrastructures.abstracts.LegalTemplateService;
import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.ContractCoTenantRepository;
import com.isums.contractservice.infrastructures.repositories.ContractRelocationRequestRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
import com.isums.houseservice.grpc.HouseResponse;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Relocation orchestrator. State transitions on {@link ContractRelocationRequest}
 * are gated by {@link RelocationStateMachine}; transitions on {@link EContract}
 * are gated by {@link EContractStatus#validateTransition}.
 *
 * <p><b>Legal context (Vietnamese law)</b>:</p>
 * <ul>
 *   <li>Civil Code 2015 Art. 328 (deposit), Art. 477 (lessor's obligation to deliver
 *       fit-for-use property), Art. 408 (replacement contract).</li>
 *   <li>Housing Law 2023 Art. 163 (lessee right to terminate / change premises),
 *       Art. 172 (housing fitness requirements).</li>
 *   <li>Electronic Transactions Law 2023 (replacement contract must be e-signed
 *       via VNPT eContract).</li>
 * </ul>
 *
 * <p><b>NOTE on contract-term continuity (B11 — known limitation):</b> Replacement
 * contracts copy stored fields from the old EContract entity (rent amount, dates,
 * tenant identity, policies, late penalty, etc.) but contract-form-only fields
 * such as payCycle, landlordNoticeDays, cureDays, maxLateDays, dispute forum,
 * forceMajeureNoticeHours, taxFeeNote, earlyTerminationPenalty,
 * landlordBreachCompensation are not persisted on the entity and will fall back
 * to {@link CreateEContractRequest} defaults. Manager should explicitly review
 * the generated replacement contract before VNPT signing.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractRelocationServiceImpl implements ContractRelocationService {

    private static final List<RelocationRequestStatus> OPEN_STATUSES = List.of(
            RelocationRequestStatus.REQUESTED,
            RelocationRequestStatus.QUOTED,
            RelocationRequestStatus.APPROVED,
            RelocationRequestStatus.CONTRACT_CREATED,
            RelocationRequestStatus.ADDITIONAL_PAYMENT_PENDING,
            RelocationRequestStatus.REFUND_PENDING);

    private final EContractRepository contractRepo;
    private final ContractRelocationRequestRepository relocationRepo;
    private final ContractCoTenantRepository coTenantRepo;
    private final EContractService eContractService;
    private final HouseGrpcClient houseGrpc;
    private final UserGrpcClient userGrpc;
    private final OutboxPublisher outboxPublisher;
    private final S3Service s3Service;
    private final LegalTemplateService legalTemplateService;
    private final RenewalRequestRepository renewalRequestRepo;

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto submit(UUID contractId, UUID actorId, CreateRelocationRequest request) {
        UUID tenantId = resolveInternalTenantId(actorId);
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));

        if (!contract.getUserId().equals(tenantId)) {
            throw new AccessDeniedException("You may only request relocation for your own contract");
        }
        if (contract.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException("Relocation is only allowed after the contract is signed. Current: "
                    + contract.getStatus());
        }
        if (Objects.equals(contract.getHouseId(), request.requestedHouseId())) {
            throw new IllegalArgumentException("Requested house must be different from the current house");
        }
        if (relocationRepo.existsByOldContractIdAndStatusIn(contractId, OPEN_STATUSES)) {
            throw new IllegalStateException("This contract already has an open relocation request");
        }

        HouseResponse requestedHouse = houseGrpc.getHouseById(request.requestedHouseId());
        if (requestedHouse == null) {
            throw new NotFoundException("Requested house not found: " + request.requestedHouseId());
        }

        DepositStatus snapshot = inferDepositStatus(contract);
        boolean activeLeaseUpgrade = isActiveLeaseUpgrade(contract, request);
        // B10: sensible default — desiredMoveDate from request, else +7 days for active lease
        // (Housing Law 2023 Art. 172 implies notice period for changes), else null for pre-handover
        Instant desiredMoveDate = request.desiredMoveDate() != null
                ? request.desiredMoveDate()
                : (activeLeaseUpgrade ? Instant.now().plus(7, ChronoUnit.DAYS) : null);
        String legalBasisSnapshot = activeLeaseUpgrade
                ? legalTemplateService.resolveSnapshot(
                        LegalTemplateKey.RELOCATION_ACTIVE_LEASE_UPGRADE_BASIS.name(),
                        contract.getContractLanguage())
                : null;
        ContractRelocationRequest saved = relocationRepo.save(ContractRelocationRequest.builder()
                .oldContractId(contract.getId())
                .tenantId(contract.getUserId())
                .oldHouseId(contract.getHouseId())
                .requestedHouseId(request.requestedHouseId())
                .status(RelocationRequestStatus.REQUESTED)
                .requestKind(activeLeaseUpgrade
                        ? RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE
                        : RelocationRequestKind.PRE_HANDOVER_TENANT_REQUEST)
                .faultParty(RelocationFaultParty.TENANT)
                .resolutionType(RelocationResolutionType.REPLACE_HOUSE)
                .depositStatusSnapshot(snapshot)
                .depositAmount(nz(contract.getDepositAmount()))
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
                .desiredMoveDate(desiredMoveDate)
                .occupantCount(request.occupantCount())
                .legalBasis(legalBasisSnapshot)
                .tenantReason(request.reason())
                .requestedBy(tenantId)
                .requestedAt(Instant.now())
                .build());

        log.info("[Relocation] REQUESTED id={} oldContractId={} requestedHouseId={}",
                saved.getId(), contractId, request.requestedHouseId());
        return toDto(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto reportLandlordFaultByContractNumber(
            String contractNumber,
            UUID actorId,
            boolean landlord,
            CreateLandlordFaultRelocationRequest request,
            List<MultipartFile> evidenceFiles) {
        if (evidenceFiles == null || evidenceFiles.stream().noneMatch(file -> file != null && !file.isEmpty())) {
            throw new IllegalArgumentException("At least one evidence image is required");
        }
        EContract contract = findByContractNumber(contractNumber);
        return reportLandlordFault(contract, actorId, landlord, request, evidenceFiles);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto reportLandlordFaultByContractId(
            UUID contractId,
            UUID actorId,
            boolean landlord,
            CreateLandlordFaultRelocationRequest request,
            List<MultipartFile> evidenceFiles) {
        if (evidenceFiles == null || evidenceFiles.stream().noneMatch(file -> file != null && !file.isEmpty())) {
            throw new IllegalArgumentException("At least one evidence image is required");
        }
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        return reportLandlordFault(contract, actorId, landlord, request, evidenceFiles);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<ContractRelocationRequestDto> getActiveByContractId(UUID contractId) {
        return relocationRepo
                .findFirstByOldContractIdAndStatusInOrderByCreatedAtDesc(contractId, OPEN_STATUSES)
                .map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<ContractRelocationRequestDto> getLinkByContractId(UUID contractId) {
        java.util.Optional<ContractRelocationRequest> asOld =
                relocationRepo.findFirstByOldContractIdOrderByCreatedAtDesc(contractId);
        if (asOld.isPresent()) {
            return asOld.map(this::toDto);
        }
        return relocationRepo.findByNewContractId(contractId).map(this::toDto);
    }

    private ContractRelocationRequestDto reportLandlordFault(
            EContract contract,
            UUID actorId,
            boolean landlord,
            CreateLandlordFaultRelocationRequest request,
            List<MultipartFile> evidenceFiles) {
        if (contract.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException("Landlord-fault relocation can only be reported after signing. Current: "
                    + contract.getStatus());
        }
        if (request.reportReason() == null || request.reportReason().isBlank()) {
            throw new IllegalArgumentException("Report reason is required");
        }
        if (request.reportReason().length() > 1000) {
            throw new IllegalArgumentException("Report reason must not exceed 1000 characters");
        }
        if (request.recommendedHouseId() != null
                && Objects.equals(contract.getHouseId(), request.recommendedHouseId())) {
            throw new IllegalArgumentException("Recommended replacement house must be different from the current house");
        }
        if (relocationRepo.existsByOldContractIdAndStatusIn(contract.getId(), OPEN_STATUSES)) {
            throw new IllegalStateException("This contract already has an open relocation request");
        }

        if (request.recommendedHouseId() != null
                && houseGrpc.getHouseById(request.recommendedHouseId()) == null) {
            throw new NotFoundException("Recommended house not found: " + request.recommendedHouseId());
        }

        UUID staffInternalId = resolveInternalTenantId(actorId);
        assertCanActOnHouse(staffInternalId, landlord, contract.getHouseId());

        String evidence = uploadEvidenceFiles(contract.getId(), actorId, evidenceFiles, request.evidence());
        DepositStatus snapshot = inferDepositStatus(contract);
        Instant now = Instant.now();
        String legalBasisSnapshot = legalTemplateService.resolveSnapshot(
                LegalTemplateKey.RELOCATION_LANDLORD_FAULT_BASIS.name(),
                contract.getContractLanguage());
        ContractRelocationRequest saved = relocationRepo.save(ContractRelocationRequest.builder()
                .oldContractId(contract.getId())
                .tenantId(contract.getUserId())
                .oldHouseId(contract.getHouseId())
                .requestedHouseId(request.recommendedHouseId())
                .status(RelocationRequestStatus.REQUESTED)
                .requestKind(RelocationRequestKind.LANDLORD_FAULT_UNINHABITABLE)
                .faultParty(RelocationFaultParty.LANDLORD)
                .resolutionType(request.recommendedHouseId() != null
                        ? RelocationResolutionType.REPLACE_HOUSE
                        : RelocationResolutionType.REFUND_TERMINATE)
                .depositStatusSnapshot(snapshot)
                .depositAmount(nz(contract.getDepositAmount()))
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
                .staffReportReason(request.reportReason())
                .staffEvidence(evidence)
                .legalBasis(legalBasisSnapshot)
                .tenantReason("Staff reported the current house is not suitable for occupancy.")
                .requestedBy(staffInternalId)
                .staffReportedBy(staffInternalId)
                .requestedAt(now)
                .staffReportedAt(now)
                .build());

        log.info("[Relocation] LANDLORD_FAULT_REPORTED id={} oldContractId={} recommendedHouseId={}",
                saved.getId(), contract.getId(), request.recommendedHouseId());

        outboxPublisher.enqueue(
                "relocation.reported",
                contract.getId().toString(),
                RelocationReportedEvent.builder()
                        .relocationRequestId(saved.getId())
                        .oldContractId(contract.getId())
                        .oldHouseId(contract.getHouseId())
                        .tenantId(contract.getUserId())
                        .staffReportedBy(staffInternalId)
                        .reportReason(request.reportReason())
                        .reportedAt(now)
                        .build());

        return toDto(saved);
    }

    private EContract findByContractNumber(String contractNumber) {
        if (contractNumber == null || contractNumber.isBlank()) {
            throw new IllegalArgumentException("Contract number is required");
        }
        String normalized = contractNumber.trim();
        return contractRepo.findByDocumentNoIgnoreCase(normalized)
                .or(() -> contractRepo.findByDocumentIdIgnoreCase(normalized))
                .orElseThrow(() -> new NotFoundException("Signed contract not found by number: " + normalized));
    }

    private String uploadEvidenceFiles(
            UUID contractId,
            UUID actorId,
            List<MultipartFile> evidenceFiles,
            String fallbackEvidence) {
        List<MultipartFile> files = evidenceFiles == null
                ? List.of()
                : evidenceFiles.stream()
                        .filter(file -> file != null && !file.isEmpty())
                        .toList();
        if (files.isEmpty()) {
            return fallbackEvidence == null || fallbackEvidence.isBlank() ? null : fallbackEvidence.trim();
        }
        if (files.size() > 5) {
            throw new IllegalArgumentException("You can upload at most 5 evidence images");
        }
        return files.stream()
                .map(file -> s3Service.uploadRelocationEvidence(file, contractId, actorId))
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractRelocationRequestDto> getMine(UUID actorId) {
        UUID tenantId = resolveInternalTenantId(actorId);
        return relocationRepo.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractRelocationRequestDto> getAll(UUID actorId, boolean landlord) {
        List<ContractRelocationRequest> requests = relocationRepo.findAllByOrderByCreatedAtDesc();
        if (landlord) {
            return requests.stream().map(this::toDto).toList();
        }

        UUID internalUserId = resolveInternalTenantId(actorId);
        Set<UUID> managedHouseIds = houseGrpc.getManagedHouseIds(internalUserId);
        return requests.stream()
                .filter(r -> managedHouseIds.contains(r.getOldHouseId())
                        || (r.getRequestedHouseId() != null && managedHouseIds.contains(r.getRequestedHouseId()))
                        || (r.getApprovedHouseId() != null && managedHouseIds.contains(r.getApprovedHouseId()))
                        || internalUserId.equals(r.getStaffReportedBy()))
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto review(
            UUID requestId,
            UUID actorId,
            boolean landlord,
            ReviewRelocationRequest request) {
        ContractRelocationRequest relocation = findRequest(requestId);
        if (relocation.getStatus() != RelocationRequestStatus.REQUESTED) {
            throw new IllegalStateException("Only REQUESTED relocation requests can be reviewed. Current: "
                    + relocation.getStatus());
        }

        UUID internalReviewerId = resolveInternalTenantId(actorId);
        UUID prospectiveHouseId = request.approvedHouseId() != null
                ? request.approvedHouseId()
                : relocation.getRequestedHouseId();
        assertCanActOnHouse(internalReviewerId, landlord,
                relocation.getOldHouseId(), prospectiveHouseId);

        relocation.setReviewedBy(internalReviewerId);
        relocation.setReviewedAt(Instant.now());
        relocation.setManagerNote(request.managerNote());

        if (!Boolean.TRUE.equals(request.approved())) {
            transition(relocation, RelocationRequestStatus.REJECTED);
            return toDto(relocationRepo.save(relocation));
        }

        EContract old = contractRepo.findById(relocation.getOldContractId())
                .orElseThrow(() -> new NotFoundException("Contract not found: " + relocation.getOldContractId()));

        RelocationResolutionType resolutionType = request.resolutionType() != null
                ? request.resolutionType()
                : relocation.getResolutionType();
        if (resolutionType == null) {
            resolutionType = RelocationResolutionType.REPLACE_HOUSE;
        }
        relocation.setResolutionType(resolutionType);
        relocation.setLegalBasis(request.legalBasis() != null && !request.legalBasis().isBlank()
                ? request.legalBasis()
                : relocation.getLegalBasis());

        if (resolutionType == RelocationResolutionType.REFUND_TERMINATE) {
            approveRefundTermination(relocation, old, request);
            log.info("[Relocation] REFUND_TERMINATE_APPROVED id={} oldContractId={} amount={}",
                    relocation.getId(), old.getId(), relocation.getRefundAmount());
            return toDto(relocationRepo.save(relocation));
        }

        approveReplacement(relocation, old, request);

        log.info("[Relocation] APPROVED id={} approvedHouseId={} handling={} transfer={} additional={}",
                relocation.getId(), relocation.getApprovedHouseId(), relocation.getDepositHandling(),
                relocation.getTransferredDepositAmount(), relocation.getAdditionalDepositAmount());
        return toDto(relocationRepo.save(relocation));
    }

    private void approveReplacement(
            ContractRelocationRequest relocation,
            EContract old,
            ReviewRelocationRequest request) {
        UUID approvedHouseId = request.approvedHouseId() != null
                ? request.approvedHouseId()
                : relocation.getRequestedHouseId();
        if (approvedHouseId == null) {
            throw new IllegalArgumentException("Approved house is required for replacement resolution");
        }

        HouseResponse approvedHouse = houseGrpc.getHouseById(approvedHouseId);
        if (approvedHouse == null) {
            throw new NotFoundException("Approved house not found: " + approvedHouseId);
        }

        long newDeposit = request.newDepositAmount() != null ? request.newDepositAmount() : nz(old.getDepositAmount());
        long depositAmount = nz(old.getDepositAmount());
        boolean activeLeaseUpgrade = relocation.getRequestKind() == RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE;
        DepositHandling handling = request.depositHandling() != null
                ? request.depositHandling()
                : defaultHandling(relocation.getFaultParty(), relocation.getDepositStatusSnapshot(),
                        RelocationResolutionType.REPLACE_HOUSE);
        if (handling == DepositHandling.REFUND_TO_TENANT) {
            throw new IllegalArgumentException("Use REFUND_TERMINATE resolution for deposit refund");
        }
        if (activeLeaseUpgrade && handling == DepositHandling.CANCEL_PENDING_DEPOSIT) {
            throw new IllegalArgumentException("Active lease relocation cannot cancel an already paid deposit");
        }
        if (relocation.getFaultParty() == RelocationFaultParty.LANDLORD
                && handling == DepositHandling.FORFEIT) {
            throw new IllegalArgumentException("Cannot forfeit tenant deposit for landlord-fault relocation");
        }

        long oldRentProrated = nz(request.oldRentProratedAmount());
        long oldUtilities = nz(request.oldUtilitiesAmount());
        long oldDamage = nz(request.oldDamageAmount());
        long adminFee = nz(request.adminFeeAmount());
        long deductibleFromDeposit = oldUtilities + oldDamage;
        long refundableDeposit = request.refundableDepositAmount() != null
                ? clamp(request.refundableDepositAmount(), 0L, depositAmount)
                : Math.max(0L, depositAmount - deductibleFromDeposit);

        // B16: REFUND_TO_TENANT was already rejected above, so no case for it here.
        long transferred = switch (handling) {
            case TRANSFER_TO_REPLACEMENT -> Math.min(activeLeaseUpgrade ? refundableDeposit : depositAmount, newDeposit);
            case PARTIAL_TRANSFER -> clamp(
                    nz(request.transferredDepositAmount()),
                    0L,
                    Math.min(activeLeaseUpgrade ? refundableDeposit : depositAmount, newDeposit));
            case FORFEIT, CANCEL_PENDING_DEPOSIT -> 0L;
            case REFUND_TO_TENANT -> throw new IllegalStateException(
                    "Unreachable: REFUND_TO_TENANT rejected above in REPLACE_HOUSE flow");
        };
        long forfeit = handling == DepositHandling.FORFEIT
                ? clamp(request.forfeitAmount() != null ? request.forfeitAmount() : depositAmount, 0L, depositAmount)
                : 0L;
        long additional = request.additionalDepositAmount() != null
                ? Math.max(0L, request.additionalDepositAmount())
                : Math.max(0L, newDeposit - transferred);
        long settlement = request.settlementAmount() != null
                ? Math.max(0L, request.settlementAmount())
                : oldRentProrated + oldUtilities + oldDamage + adminFee;
        long uncoveredDeductions = Math.max(0L, deductibleFromDeposit - depositAmount);
        long totalAdditional = request.totalAdditionalPaymentAmount() != null
                ? Math.max(0L, request.totalAdditionalPaymentAmount())
                : additional + oldRentProrated + adminFee + uncoveredDeductions;
        long refund = Math.max(0L, (activeLeaseUpgrade ? refundableDeposit : depositAmount) - transferred);

        transition(relocation, activeLeaseUpgrade
                ? RelocationRequestStatus.QUOTED
                : RelocationRequestStatus.APPROVED);
        relocation.setApprovedHouseId(approvedHouseId);
        relocation.setDepositHandling(handling);
        relocation.setTransferredDepositAmount(transferred);
        relocation.setForfeitAmount(forfeit);
        relocation.setAdditionalDepositAmount(additional);
        relocation.setRefundAmount(refund);
        relocation.setOldRentProratedAmount(oldRentProrated);
        relocation.setOldUtilitiesAmount(oldUtilities);
        relocation.setOldDamageAmount(oldDamage);
        relocation.setAdminFeeAmount(adminFee);
        relocation.setSettlementAmount(settlement);
        relocation.setRefundableDepositAmount(activeLeaseUpgrade ? refundableDeposit : 0L);
        relocation.setTotalAdditionalPaymentAmount(activeLeaseUpgrade ? totalAdditional : additional);
        relocation.setInspectionNote(request.inspectionNote());
        relocation.setNewRentAmount(request.newRentAmount() != null ? request.newRentAmount() : old.getRentAmount());
        relocation.setNewDepositAmount(newDeposit);
        // B10: prefer manager override, then desiredMoveDate (active lease), else now()
        relocation.setNewStartAt(resolveNewStartAt(request, relocation, old, activeLeaseUpgrade));
        relocation.setNewEndAt(request.newEndAt() != null ? request.newEndAt() : old.getEndAt());
        relocation.setNewHandoverDate(request.newHandoverDate() != null
                ? request.newHandoverDate()
                : (relocation.getDesiredMoveDate() != null ? relocation.getDesiredMoveDate() : Instant.now()));
    }

    private Instant resolveNewStartAt(
            ReviewRelocationRequest request,
            ContractRelocationRequest relocation,
            EContract old,
            boolean activeLeaseUpgrade) {
        if (request.newStartAt() != null) {
            return request.newStartAt();
        }
        if (activeLeaseUpgrade && relocation.getDesiredMoveDate() != null) {
            return relocation.getDesiredMoveDate();
        }
        // For pre-handover or unspecified active-lease, the old contract was never
        // physically used so Instant.now() is safer than backdated old.startAt.
        return Instant.now();
    }

    private void approveRefundTermination(
            ContractRelocationRequest relocation,
            EContract old,
            ReviewRelocationRequest request) {
        if (relocation.getFaultParty() != RelocationFaultParty.LANDLORD) {
            throw new IllegalArgumentException("Refund termination is only available for landlord-fault relocation");
        }

        DepositHandling handling = defaultHandling(relocation.getFaultParty(), relocation.getDepositStatusSnapshot(),
                RelocationResolutionType.REFUND_TERMINATE);
        long depositAmount = nz(old.getDepositAmount());
        long refundAmount = handling == DepositHandling.REFUND_TO_TENANT
                ? clamp(request.refundAmount() != null ? request.refundAmount() : depositAmount, 0L, depositAmount)
                : 0L;
        if (handling == DepositHandling.REFUND_TO_TENANT && depositAmount > 0L && refundAmount <= 0L) {
            throw new IllegalArgumentException("Refund amount must be greater than zero for a paid deposit");
        }
        Instant refundDueAt = request.refundDueAt() != null
                ? request.refundDueAt()
                : Instant.now().plus(nzDays(old.getDepositRefundDays(), 7), ChronoUnit.DAYS);

        relocation.setApprovedHouseId(null);
        relocation.setDepositHandling(handling);
        relocation.setTransferredDepositAmount(0L);
        relocation.setForfeitAmount(0L);
        relocation.setAdditionalDepositAmount(0L);
        relocation.setRefundAmount(refundAmount);
        relocation.setRefundDueAt(refundDueAt);
        relocation.setNewRentAmount(null);
        relocation.setNewDepositAmount(null);
        relocation.setNewStartAt(null);
        relocation.setNewEndAt(null);
        relocation.setNewHandoverDate(null);

        if (handling == DepositHandling.CANCEL_PENDING_DEPOSIT) {
            transition(relocation, RelocationRequestStatus.COMPLETED);
            relocation.setCompletedAt(Instant.now());
            old.getStatus().validateTransition(EContractStatus.TERMINATED);
            old.setStatus(EContractStatus.TERMINATED);
            old.setTerminatedAt(Instant.now());
            old.setTerminatedBy(relocation.getReviewedBy());
            old.setTerminatedReason("Landlord fault: house is not suitable for occupancy");
            contractRepo.save(old);
            publishCancelPendingDeposit(old, relocation);
            publishContractReplaced(old, null, relocation);
            return;
        }

        if (refundAmount == 0L) {
            transition(relocation, RelocationRequestStatus.COMPLETED);
            relocation.setCompletedAt(Instant.now());
            old.getStatus().validateTransition(EContractStatus.TERMINATED);
            old.setStatus(EContractStatus.TERMINATED);
            old.setTerminatedAt(Instant.now());
            old.setTerminatedBy(relocation.getReviewedBy());
            old.setTerminatedReason("Landlord fault: house is not suitable for occupancy");
            contractRepo.save(old);
            publishContractReplaced(old, null, relocation);
            return;
        }

        old.getStatus().validateTransition(EContractStatus.DEPOSIT_REFUND_PENDING);
        old.setStatus(EContractStatus.DEPOSIT_REFUND_PENDING);
        old.setTerminatedBy(relocation.getReviewedBy());
        old.setTerminatedReason("Landlord fault: house is not suitable for occupancy");
        contractRepo.save(old);

        transition(relocation, RelocationRequestStatus.REFUND_PENDING);
        publishDepositRefundConfirmed(old, refundAmount, refundDueAt, relocation);
        publishContractReplaced(old, null, relocation);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto acceptQuote(UUID requestId, UUID actorId) {
        UUID tenantId = resolveInternalTenantId(actorId);
        ContractRelocationRequest relocation = findRequest(requestId);
        if (!relocation.getTenantId().equals(tenantId)) {
            throw new AccessDeniedException("You may only accept your own relocation quote");
        }
        if (relocation.getRequestKind() != RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE) {
            throw new IllegalStateException("Only active lease relocation quotes require tenant acceptance");
        }
        if (relocation.getStatus() == RelocationRequestStatus.APPROVED) {
            return toDto(relocation);
        }
        if (relocation.getStatus() != RelocationRequestStatus.QUOTED) {
            throw new IllegalStateException("Only QUOTED relocation requests can be accepted. Current: "
                    + relocation.getStatus());
        }
        transition(relocation, RelocationRequestStatus.APPROVED);
        relocation.setTenantAcceptedAt(Instant.now());
        ContractRelocationRequest saved = relocationRepo.save(relocation);
        log.info("[Relocation] QUOTE_ACCEPTED id={} oldContractId={} tenantId={}",
                saved.getId(), saved.getOldContractId(), tenantId);
        return toDto(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public EContractDto createReplacementContract(
            UUID requestId,
            UUID actorId,
            boolean landlord,
            String jwtToken) {
        ContractRelocationRequest relocation = findRequest(requestId);
        if (relocation.getStatus() != RelocationRequestStatus.APPROVED) {
            throw new IllegalStateException("Replacement contract can only be created after approval. Current: "
                    + relocation.getStatus());
        }
        if (relocation.getResolutionType() == RelocationResolutionType.REFUND_TERMINATE) {
            throw new IllegalStateException("Refund termination requests do not create replacement contracts");
        }

        UUID internalActorId = resolveInternalTenantId(actorId);
        assertCanActOnHouse(internalActorId, landlord,
                relocation.getOldHouseId(), relocation.getApprovedHouseId());

        EContract old = contractRepo.findById(relocation.getOldContractId())
                .orElseThrow(() -> new NotFoundException("Contract not found: " + relocation.getOldContractId()));

        // B14: Pre-validate fields the replacement draft strictly needs (CreateEContractRequest @NotNull).
        if (relocation.getNewRentAmount() == null) {
            throw new IllegalStateException(
                    "Cannot create replacement: new rent amount missing from review. "
                            + "Manager must specify newRentAmount during /review.");
        }
        if (relocation.getNewDepositAmount() == null) {
            throw new IllegalStateException(
                    "Cannot create replacement: new deposit amount missing from review.");
        }
        if (relocation.getNewStartAt() == null || relocation.getNewEndAt() == null) {
            throw new IllegalStateException(
                    "Cannot create replacement: new start/end dates missing from review.");
        }

        UserResponse tenant = userGrpc.getUserById(old.getUserId().toString());

        CreateEContractRequest newReq = CreateEContractRequest.builder()
                .isNewAccount(false)
                .name(old.getTenantName())
                .email(tenant.getEmail())
                .houseId(relocation.getApprovedHouseId())
                .startDate(relocation.getNewStartAt())
                .endDate(relocation.getNewEndAt())
                .rentAmount(relocation.getNewRentAmount())
                .payDate(old.getPayDate())
                .depositAmount(relocation.getNewDepositAmount())
                .depositDate(relocation.getNewStartAt())
                .handoverDate(relocation.getNewHandoverDate() != null
                        ? relocation.getNewHandoverDate()
                        : relocation.getNewStartAt())
                .lateDays(old.getLateDays())
                .latePenaltyPercent(old.getLatePenaltyPercent())
                .depositRefundDays(old.getDepositRefundDays())
                .renewNoticeDays(old.getRenewNoticeDays())
                .tenantType(old.getTenantType())
                .identityNumber(old.getCccdNumber())
                .passportNumber(old.getPassportNumber())
                .passportIssueDate(old.getPassportIssueDate())
                .passportIssuePlace(old.getPassportIssuePlace())
                .passportExpiryDate(old.getPassportExpiryDate())
                .visaType(old.getVisaType())
                .visaExpiryDate(old.getVisaExpiryDate())
                .nationality(old.getNationality())
                .dateOfBirth(old.getDateOfBirth())
                .gender(old.getGender())
                .occupation(old.getOccupation())
                .permanentAddress(old.getPermanentAddress())
                .coTenants(copyCoTenants(old.getId()))
                .petPolicy(old.getPetPolicy())
                .smokingPolicy(old.getSmokingPolicy())
                .subleasePolicy(old.getSubleasePolicy())
                .visitorPolicy(old.getVisitorPolicy())
                .tempResidenceRegisterBy(old.getTempResidenceRegisterBy())
                .taxResponsibility(old.getTaxResponsibility())
                .contractLanguage(old.getContractLanguage())
                .hasPowerCutClause(old.getHasPowerCutClause())
                .build();

        com.isums.contractservice.domains.dtos.ReplacementContext replacementCtx =
                new com.isums.contractservice.domains.dtos.ReplacementContext(
                        old.getId(),
                        old.getDocumentNo(),
                        old.getCreatedAt(),
                        relocation.getId(),
                        relocation.getRequestKind(),
                        nz(old.getDepositAmount()),
                        nz(relocation.getTransferredDepositAmount()),
                        nz(relocation.getNewDepositAmount()),
                        nz(relocation.getAdditionalDepositAmount()),
                        nz(relocation.getOldRentProratedAmount()),
                        nz(relocation.getOldUtilitiesAmount()),
                        nz(relocation.getOldDamageAmount()),
                        nz(relocation.getAdminFeeAmount()),
                        nz(relocation.getTotalAdditionalPaymentAmount()),
                        nz(relocation.getRefundableDepositAmount()),
                        relocation.getInspectionNote(),
                        relocation.getLegalBasis(),
                        null,
                        null,
                        relocation.getNewHandoverDate());

        EContractDto dto;
        try {
            EContractServiceImpl.setPendingReplacementContext(replacementCtx);
            dto = eContractService.createDraft(actorId, jwtToken, newReq);
        } finally {
            EContractServiceImpl.clearPendingReplacementContext();
        }
        EContract replacement = contractRepo.findById(dto.id())
                .orElseThrow(() -> new NotFoundException("Replacement contract not found: " + dto.id()));
        replacement.setRelocationSourceContractId(old.getId());
        replacement.setTransferredDepositAmount(nz(relocation.getTransferredDepositAmount()));
        replacement.setDepositStatus(initialReplacementDepositStatus(relocation));
        contractRepo.save(replacement);

        old.setReplacedByContractId(replacement.getId());
        old.setDepositStatus(oldDepositStatusAfterRelocation(relocation));
        EContractStatus oldNextStatus = relocation.getRequestKind() == RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE
                ? EContractStatus.PENDING_REPLACEMENT_HANDOVER
                : oldContractStatusAfterRelocation(relocation);
        old.getStatus().validateTransition(oldNextStatus);
        old.setStatus(oldNextStatus);
        contractRepo.save(old);

        relocation.setNewContractId(replacement.getId());
        transition(relocation, RelocationRequestStatus.CONTRACT_CREATED);
        relocation.setContractCreatedAt(Instant.now());
        relocationRepo.save(relocation);

        if (relocation.getRequestKind() != RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE) {
            publishContractReplaced(old, replacement.getId(), relocation);
        }

        if (relocation.getDepositHandling() == DepositHandling.CANCEL_PENDING_DEPOSIT) {
            publishCancelPendingDeposit(old, relocation);
            relocationRepo.save(relocation);
        }

        log.info("[Relocation] CONTRACT_CREATED id={} oldContractId={} newContractId={} oldStatus={}",
                relocation.getId(), old.getId(), replacement.getId(), oldNextStatus);
        return dto;
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto cancelByTenant(UUID requestId, UUID actorId) {
        UUID tenantId = resolveInternalTenantId(actorId);
        ContractRelocationRequest relocation = findRequest(requestId);
        if (!relocation.getTenantId().equals(tenantId)) {
            throw new AccessDeniedException("You may only cancel your own relocation requests");
        }

        if (relocation.getStatus() != RelocationRequestStatus.REQUESTED
                && relocation.getStatus() != RelocationRequestStatus.QUOTED) {
            throw new IllegalStateException(
                    "Tenant can only cancel a relocation in REQUESTED or QUOTED state. Current: "
                            + relocation.getStatus());
        }
        transition(relocation, RelocationRequestStatus.CANCELLED);
        relocation.setCompletedAt(Instant.now());
        ContractRelocationRequest saved = relocationRepo.save(relocation);
        log.info("[Relocation] CANCELLED_BY_TENANT id={} tenantId={}", saved.getId(), tenantId);
        return toDto(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto cancelByManager(UUID requestId, UUID actorId, boolean landlord) {
        ContractRelocationRequest relocation = findRequest(requestId);
        UUID internalActorId = resolveInternalTenantId(actorId);
        assertCanActOnHouse(internalActorId, landlord,
                relocation.getOldHouseId(), relocation.getApprovedHouseId(), relocation.getRequestedHouseId());

        Set<RelocationRequestStatus> cancellable = Set.of(
                RelocationRequestStatus.REQUESTED,
                RelocationRequestStatus.QUOTED,
                RelocationRequestStatus.APPROVED);
        if (!cancellable.contains(relocation.getStatus())) {
            throw new IllegalStateException(
                    "Manager can only cancel before contract creation. Current: " + relocation.getStatus());
        }
        transition(relocation, RelocationRequestStatus.CANCELLED);
        relocation.setReviewedBy(internalActorId);
        relocation.setReviewedAt(Instant.now());
        relocation.setCompletedAt(Instant.now());
        ContractRelocationRequest saved = relocationRepo.save(relocation);
        log.info("[Relocation] CANCELLED_BY_MANAGER id={} actor={}", saved.getId(), internalActorId);
        return toDto(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "user-contracts", allEntries = true),
            @CacheEvict(value = "user-house-access", allEntries = true),
            @CacheEvict(value = "marketplace-bookable", allEntries = true),
            @CacheEvict(value = "marketplace-locked", allEntries = true)
    })
    public ContractRelocationRequestDto confirmHandover(UUID requestId, UUID actorId, boolean landlord) {
        ContractRelocationRequest relocation = findRequest(requestId);
        if (relocation.getRequestKind() != RelocationRequestKind.ACTIVE_LEASE_TENANT_UPGRADE) {
            throw new IllegalStateException(
                    "Handover confirmation only applies to active-lease relocations. "
                            + "Other relocation kinds terminate the old contract at /replacement-contract.");
        }
        if (relocation.getStatus() != RelocationRequestStatus.CONTRACT_CREATED) {
            throw new IllegalStateException(
                    "Handover can only be confirmed for CONTRACT_CREATED relocations. Current: "
                            + relocation.getStatus());
        }

        UUID internalActorId = resolveInternalTenantId(actorId);
        assertCanActOnHouse(internalActorId, landlord,
                relocation.getOldHouseId(), relocation.getApprovedHouseId());

        EContract newContract = contractRepo.findById(relocation.getNewContractId())
                .orElseThrow(() -> new NotFoundException(
                        "Replacement contract not found: " + relocation.getNewContractId()));
        if (newContract.getStatus() != EContractStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Replacement contract must be signed (COMPLETED) before handover can be confirmed. Current: "
                            + newContract.getStatus());
        }

        EContract old = contractRepo.findById(relocation.getOldContractId())
                .orElseThrow(() -> new NotFoundException(
                        "Old contract not found: " + relocation.getOldContractId()));
        if (old.getStatus() != EContractStatus.PENDING_REPLACEMENT_HANDOVER) {
            throw new IllegalStateException(
                    "Old contract is not awaiting handover. Current: " + old.getStatus());
        }

        old.getStatus().validateTransition(EContractStatus.REPLACED_AFTER_DEPOSIT);
        old.setStatus(EContractStatus.REPLACED_AFTER_DEPOSIT);
        old.setTerminatedAt(Instant.now());
        old.setTerminatedBy(internalActorId);
        old.setTerminatedReason("Handover confirmed for relocation request " + relocation.getId());
        contractRepo.save(old);

        transition(relocation, RelocationRequestStatus.COMPLETED);
        relocation.setCompletedAt(Instant.now());
        ContractRelocationRequest saved = relocationRepo.save(relocation);
        publishContractReplaced(old, newContract.getId(), relocation);
        log.info("[Relocation] HANDOVER_CONFIRMED id={} oldContractId={} newContractId={} actor={}",
                saved.getId(), old.getId(), newContract.getId(), internalActorId);
        return toDto(saved);
    }

    private void transition(ContractRelocationRequest relocation, RelocationRequestStatus next) {
        RelocationStateMachine.validate(relocation.getStatus(), next);
        relocation.setStatus(next);
    }

    private void publishContractReplaced(
            EContract old,
            UUID newContractId,
            ContractRelocationRequest relocation) {
        boolean landlordFault = relocation.getFaultParty() == RelocationFaultParty.LANDLORD;
        UUID newHouseId = relocation.getApprovedHouseId() != null
                ? relocation.getApprovedHouseId()
                : relocation.getRequestedHouseId();
        ContractReplacedEvent event = ContractReplacedEvent.builder()
                .messageId(UUID.randomUUID().toString())
                .oldContractId(old.getId())
                .newContractId(newContractId)
                .oldHouseId(old.getHouseId())
                .newHouseId(newHouseId)
                .tenantId(old.getUserId())
                .keepHouseUnavailable(landlordFault)
                .depositHandling(relocation.getDepositHandling() != null
                        ? relocation.getDepositHandling().name()
                        : null)
                .transferredDepositAmount(nz(relocation.getTransferredDepositAmount()))
                .reason(landlordFault
                        ? (relocation.getStaffReportReason() != null && !relocation.getStaffReportReason().isBlank()
                                ? relocation.getStaffReportReason()
                                : "Landlord-fault relocation")
                        : "Tenant-initiated relocation")
                .replacedAt(Instant.now())
                .build();
        outboxPublisher.enqueue(
                "contract.replaced",
                old.getId().toString(),
                event,
                event.getMessageId());
    }

    private void publishCancelPendingDeposit(EContract old, ContractRelocationRequest relocation) {
        CancelDepositInvoiceRequestedEvent event = new CancelDepositInvoiceRequestedEvent(
                UUID.randomUUID().toString(),
                old.getId(),
                old.getUserId(),
                old.getHouseId(),
                relocation.getFaultParty() == RelocationFaultParty.LANDLORD
                        ? "Landlord fault relocation before deposit payment"
                        : "Customer relocation before deposit payment",
                Instant.now());
        outboxPublisher.enqueue(
                "contract.deposit-invoice-cancel-requested",
                old.getId().toString(),
                event,
                event.messageId());
        relocation.setDepositStatusSnapshot(DepositStatus.UNPAID);
    }

    private void publishDepositRefundConfirmed(
            EContract old,
            long refundAmount,
            Instant refundDueAt,
            ContractRelocationRequest relocation) {
        UserResponse tenant = userGrpc.getUserById(old.getUserId().toString());
        DepositRefundConfirmedEvent event = DepositRefundConfirmedEvent.builder()
                .contractId(old.getId())
                .houseId(old.getHouseId())
                .tenantId(old.getUserId())
                .tenantEmail(tenant.getEmail())
                .refundAmount(refundAmount)
                .note(refundNote(refundDueAt, relocation))
                .messageId(UUID.randomUUID().toString())
                .build();
        outboxPublisher.enqueue(
                "contract.deposit-refund.confirmed",
                old.getId().toString(),
                event,
                event.getMessageId());
    }

    private String refundNote(Instant refundDueAt, ContractRelocationRequest relocation) {
        String reason = relocation.getStaffReportReason() != null && !relocation.getStaffReportReason().isBlank()
                ? relocation.getStaffReportReason()
                : "House is not suitable for occupancy";
        return "Landlord-fault relocation refund. Due at: " + refundDueAt + ". Reason: " + reason;
    }

    private List<CoTenantDto> copyCoTenants(UUID contractId) {
        return coTenantRepo.findByContractId(contractId)
                .stream()
                .map(this::toCoTenantDto)
                .toList();
    }

    private CoTenantDto toCoTenantDto(ContractCoTenant coTenant) {
        return CoTenantDto.builder()
                .fullName(coTenant.getFullName())
                .identityNumber(coTenant.getIdentityNumber())
                .identityType(coTenant.getIdentityType())
                .dateOfBirth(coTenant.getDateOfBirth())
                .gender(coTenant.getGender())
                .nationality(coTenant.getNationality())
                .relationship(coTenant.getRelationship())
                .phoneNumber(coTenant.getPhoneNumber())
                .passportNumber(coTenant.getPassportNumber())
                .visaType(coTenant.getVisaType())
                .visaExpiryDate(coTenant.getVisaExpiryDate())
                .build();
    }

    private DepositStatus initialReplacementDepositStatus(ContractRelocationRequest relocation) {
        long due = Math.max(0L, nz(relocation.getNewDepositAmount()) - nz(relocation.getTransferredDepositAmount()));
        if (due == 0L && nz(relocation.getTransferredDepositAmount()) > 0L) {
            return DepositStatus.TRANSFERRED;
        }
        return due == 0L ? DepositStatus.PAID : DepositStatus.PENDING;
    }

    private DepositStatus oldDepositStatusAfterRelocation(ContractRelocationRequest relocation) {
        if (relocation.getDepositHandling() == null) {
            return relocation.getDepositStatusSnapshot();
        }
        return switch (relocation.getDepositHandling()) {
            case TRANSFER_TO_REPLACEMENT -> DepositStatus.TRANSFERRED;
            case PARTIAL_TRANSFER -> DepositStatus.PARTIALLY_TRANSFERRED;
            case FORFEIT -> DepositStatus.FORFEITED;
            case REFUND_TO_TENANT -> DepositStatus.REFUNDED;
            case CANCEL_PENDING_DEPOSIT -> DepositStatus.UNPAID;
        };
    }

    private EContractStatus oldContractStatusAfterRelocation(ContractRelocationRequest relocation) {
        return isPaidLike(relocation.getDepositStatusSnapshot())
                ? EContractStatus.REPLACED_AFTER_DEPOSIT
                : EContractStatus.REPLACED_BEFORE_DEPOSIT;
    }

    private DepositHandling defaultHandling(
            RelocationFaultParty faultParty,
            DepositStatus status,
            RelocationResolutionType resolutionType) {
        if (resolutionType == RelocationResolutionType.REFUND_TERMINATE) {
            return isPaidLike(status)
                    ? DepositHandling.REFUND_TO_TENANT
                    : DepositHandling.CANCEL_PENDING_DEPOSIT;
        }
        if (faultParty == RelocationFaultParty.LANDLORD) {
            return isPaidLike(status)
                    ? DepositHandling.TRANSFER_TO_REPLACEMENT
                    : DepositHandling.CANCEL_PENDING_DEPOSIT;
        }
        return switch (status) {
            case PAID, TRANSFERRED, PARTIALLY_TRANSFERRED -> DepositHandling.TRANSFER_TO_REPLACEMENT;
            case UNPAID, PENDING, FORFEITED, REFUNDED -> DepositHandling.CANCEL_PENDING_DEPOSIT;
        };
    }

    private boolean isPaidLike(DepositStatus status) {
        return status == DepositStatus.PAID
                || status == DepositStatus.TRANSFERRED
                || status == DepositStatus.PARTIALLY_TRANSFERRED;
    }

    private DepositStatus inferDepositStatus(EContract contract) {
        if (contract.getDepositStatus() != null) {
            return contract.getDepositStatus();
        }
        return nz(contract.getDepositAmount()) == 0L ? DepositStatus.PAID : DepositStatus.PENDING;
    }

    private boolean isActiveLeaseUpgrade(EContract contract, CreateRelocationRequest request) {
        if (Boolean.TRUE.equals(request.activeLease())) {
            return true;
        }
        Instant startAt = contract.getStartAt();
        Instant endAt = contract.getEndAt();
        Instant now = Instant.now();
        return isPaidLike(inferDepositStatus(contract))
                && startAt != null
                && !startAt.isAfter(now)
                && (endAt == null || endAt.isAfter(now));
    }

    private ContractRelocationRequest findRequest(UUID requestId) {
        return relocationRepo.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Relocation request not found: " + requestId));
    }

    private UUID resolveInternalTenantId(UUID callerId) {
        UserResponse user = userGrpc.getUserIdAndRoleByKeyCloakId(callerId.toString());
        return UUID.fromString(user.getId());
    }

    private void assertCanActOnHouse(UUID internalUserId, boolean landlord, UUID... candidateHouseIds) {
        if (landlord || isTechnicalStaffInContext()) {
            return;
        }
        Set<UUID> managed = houseGrpc.getManagedHouseIds(internalUserId);
        for (UUID candidate : candidateHouseIds) {
            if (candidate != null && managed.contains(candidate)) {
                return;
            }
        }
        throw new AccessDeniedException("You do not manage any region involved in this relocation request");
    }

    private static boolean isTechnicalStaffInContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TECHNICAL_STAFF".equals(a.getAuthority()));
    }

    private static final int EVIDENCE_PRESIGN_TTL_MINUTES = 60;

    private String resolveEvidenceUrls(String rawEvidence) {
        if (rawEvidence == null || rawEvidence.isBlank()) {
            return rawEvidence;
        }
        String[] entries = rawEvidence.split("\\r?\\n");
        return java.util.Arrays.stream(entries)
                .parallel()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.matches("(?i)^https?://.*")
                        ? value
                        : s3Service.presignedUrl(value, EVIDENCE_PRESIGN_TTL_MINUTES))
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
    }

    private ContractRelocationRequestDto toDto(ContractRelocationRequest r) {
        return new ContractRelocationRequestDto(
                r.getId(),
                r.getOldContractId(),
                contractNumber(r.getOldContractId()),
                r.getTenantId(),
                r.getOldHouseId(),
                r.getRequestedHouseId(),
                r.getApprovedHouseId(),
                r.getNewContractId(),
                contractNumber(r.getNewContractId()),
                r.getStatus(),
                r.getRequestKind(),
                r.getFaultParty(),
                r.getResolutionType(),
                r.getDepositStatusSnapshot(),
                r.getDepositHandling(),
                r.getDepositAmount(),
                r.getTransferredDepositAmount(),
                r.getForfeitAmount(),
                r.getAdditionalDepositAmount(),
                r.getRefundAmount(),
                r.getRefundDueAt(),
                r.getNewRentAmount(),
                r.getNewDepositAmount(),
                r.getNewStartAt(),
                r.getNewEndAt(),
                r.getNewHandoverDate(),
                r.getDesiredMoveDate(),
                r.getOccupantCount(),
                r.getOldRentProratedAmount(),
                r.getOldUtilitiesAmount(),
                r.getOldDamageAmount(),
                r.getAdminFeeAmount(),
                r.getSettlementAmount(),
                r.getRefundableDepositAmount(),
                r.getTotalAdditionalPaymentAmount(),
                r.getInspectionNote(),
                r.getTenantReason(),
                r.getStaffReportReason(),
                resolveEvidenceUrls(r.getStaffEvidence()),
                r.getLegalBasis(),
                r.getManagerNote(),
                r.getRequestedBy(),
                r.getStaffReportedBy(),
                r.getReviewedBy(),
                r.getRequestedAt(),
                r.getStaffReportedAt(),
                r.getReviewedAt(),
                r.getContractCreatedAt(),
                r.getTenantAcceptedAt(),
                r.getCompletedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private String contractNumber(UUID contractId) {
        if (contractId == null) return null;
        return contractRepo.findById(contractId)
                .map(contract -> contract.getDocumentNo() != null && !contract.getDocumentNo().isBlank()
                        ? contract.getDocumentNo()
                        : contract.getDocumentId())
                .orElse(null);
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private long nzDays(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final java.util.List<EContractStatus> PRE_COMPLETION_STATUSES = java.util.List.of(
            EContractStatus.DRAFT,
            EContractStatus.PENDING_TENANT_REVIEW,
            EContractStatus.CORRECTING,
            EContractStatus.READY,
            EContractStatus.IN_PROGRESS);

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "marketplace-bookable", key = "#actorId")
    public java.util.List<DepositBookableHouseDto> findDepositBookableHouses(UUID actorId) {
        UUID callerInternalId = resolveInternalTenantId(actorId);
        Instant now = Instant.now();
        Instant cutoff = now.plus(30, ChronoUnit.DAYS);

        java.util.List<RenewalRequestStatus> renewalClosedStatuses = java.util.List.of(
                RenewalRequestStatus.DECLINED_BY_MANAGER,
                RenewalRequestStatus.CANCELLED_BY_TENANT,
                RenewalRequestStatus.COMPLETED);

        java.util.Set<UUID> lockedHouseIds = contractRepo.findHouseIdsByStatusIn(PRE_COMPLETION_STATUSES);

        java.util.List<EContract> expiring = contractRepo.findByStatusAndEndAtBetween(
                EContractStatus.COMPLETED, now, cutoff);

        java.util.List<DepositBookableHouseDto> result = new java.util.ArrayList<>();
        for (EContract c : expiring) {
            if (callerInternalId.equals(c.getUserId())) {
                continue;
            }
            if (lockedHouseIds.contains(c.getHouseId())) {
                continue;
            }
            if (renewalRequestRepo.existsByContractIdAndStatusNotIn(c.getId(), renewalClosedStatuses)) {
                continue;
            }
            if (relocationRepo.existsByOldContractIdAndStatusIn(c.getId(), OPEN_STATUSES)) {
                continue;
            }
            HouseResponse house;
            try {
                house = houseGrpc.getHouseById(c.getHouseId());
            } catch (Exception e) {
                log.warn("[Marketplace] failed to fetch house {} for deposit-bookable contract {}: {}",
                        c.getHouseId(), c.getId(), e.getMessage());
                continue;
            }
            if (house == null) {
                continue;
            }
            String houseStatus = house.getStatus() != null ? house.getStatus().name() : null;
            if (houseStatus != null
                    && !"RENTED".equals(houseStatus)
                    && !"HOUSE_STATUS_RENTED".equals(houseStatus)
                    && !"HOUSE_STATUS_UNSPECIFIED".equals(houseStatus)) {
                continue;
            }

            Instant availableFrom = c.getEndAt().plus(
                    nzDays(c.getDepositRefundDays(), 7), ChronoUnit.DAYS);

            result.add(new DepositBookableHouseDto(
                    UUID.fromString(house.getId()),
                    house.getName(),
                    house.getAddress(),
                    house.getCity(),
                    house.getCommune(),
                    house.getWard(),
                    availableFrom,
                    c.getEndAt()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "marketplace-locked", key = "'all'")
    public java.util.Set<UUID> findLockedHouseIdsForCreate() {
        return contractRepo.findHouseIdsByStatusIn(PRE_COMPLETION_STATUSES);
    }

}
