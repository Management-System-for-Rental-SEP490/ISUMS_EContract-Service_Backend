package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.ContractRelocationRequestDto;
import com.isums.contractservice.domains.dtos.DepositBookableHouseDto;
import com.isums.contractservice.domains.dtos.CreateLandlordFaultRelocationRequest;
import com.isums.contractservice.domains.dtos.CreateRelocationRequest;
import com.isums.contractservice.domains.dtos.EContractDto;
import com.isums.contractservice.domains.dtos.ReviewRelocationRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface ContractRelocationService {

    ContractRelocationRequestDto submit(UUID contractId, UUID actorId, CreateRelocationRequest request);

    /**
     * Staff/manager/landlord reports the leased premises as unfit for occupancy.
     * Always requires at least one evidence image (Civil Code 2015 Art. 477:
     * burden of proof is on the party claiming breach of property fitness).
     */
    ContractRelocationRequestDto reportLandlordFaultByContractNumber(
            String contractNumber,
            UUID actorId,
            boolean landlord,
            CreateLandlordFaultRelocationRequest request,
            List<MultipartFile> evidenceFiles);

    ContractRelocationRequestDto reportLandlordFaultByContractId(
            UUID contractId,
            UUID actorId,
            boolean landlord,
            CreateLandlordFaultRelocationRequest request,
            List<MultipartFile> evidenceFiles);

    java.util.Optional<ContractRelocationRequestDto> getActiveByContractId(UUID contractId);

    java.util.Optional<ContractRelocationRequestDto> getLinkByContractId(UUID contractId);

    List<ContractRelocationRequestDto> getMine(UUID actorId);

    List<ContractRelocationRequestDto> getAll(UUID actorId, boolean landlord);

    ContractRelocationRequestDto review(UUID requestId, UUID actorId, boolean landlord, ReviewRelocationRequest request);

    ContractRelocationRequestDto acceptQuote(UUID requestId, UUID actorId);

    EContractDto createReplacementContract(UUID requestId, UUID actorId, boolean landlord, String jwtToken);

    /**
     * Tenant cancels their own relocation request before the manager has finalised
     * a replacement contract. Allowed in REQUESTED or QUOTED state only.
     */
    ContractRelocationRequestDto cancelByTenant(UUID requestId, UUID actorId);

    /**
     * Manager/landlord cancels a relocation request before the replacement contract
     * is created. Allowed in REQUESTED, QUOTED or APPROVED state.
     */
    ContractRelocationRequestDto cancelByManager(UUID requestId, UUID actorId, boolean landlord);

    /**
     * Manager confirms physical handover of the old premises after the replacement
     * contract has been signed (active-lease relocation). Transitions the old
     * contract from PENDING_REPLACEMENT_HANDOVER to REPLACED_AFTER_DEPOSIT and
     * marks the relocation COMPLETED.
     *
     * <p>Required by Housing Law 2023 Art. 163 + Civil Code 2015 Art. 477:
     * lessee retains right of occupancy until physical return of premises.</p>
     */
    ContractRelocationRequestDto confirmHandover(UUID requestId, UUID actorId, boolean landlord);

    /**
     * Marketplace listing of houses currently rented but eligible for early
     * deposit-booking: the active contract ends within 30 days, the tenant has
     * not requested renewal, and no relocation is in flight. Caller's own
     * contracts are excluded.
     *
     * <p>Use together with {@code GET /api/houses?status=AVAILABLE} on the FE
     * side to show the full marketplace (immediately-available + soon-available).</p>
     */
    java.util.List<DepositBookableHouseDto> findDepositBookableHouses(UUID actorId);

    java.util.Set<UUID> findLockedHouseIdsForCreate();
}
