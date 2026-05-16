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

    ContractRelocationRequestDto cancelByTenant(UUID requestId, UUID actorId);

    ContractRelocationRequestDto cancelByManager(UUID requestId, UUID actorId, boolean landlord);

    ContractRelocationRequestDto confirmHandover(UUID requestId, UUID actorId, boolean landlord);

    java.util.List<DepositBookableHouseDto> findDepositBookableHouses(UUID actorId);

    java.util.Set<UUID> findLockedHouseIdsForCreate();
}
