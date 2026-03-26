package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface EContractService {

    EContractDto createDraftEContract(UUID actorId, String jwtToken, CreateEContractRequest req);

    EContractDto getEContractById(UUID id);

    List<EContractDto> getAllEContracts();

    EContractDto updateEContractById(UUID id, UpdateEContractRequest req);

    boolean hasCccd(UUID contractId);

    void uploadCccd(UUID contractId, MultipartFile frontImage, MultipartFile backImage);

    VnptDocumentDto readyEContract(UUID contractId);

    void confirmEContract(UUID contractId, String keycloakId, String jwtToken);

    ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode);

    EContractDto getEContractOutSystem(String processCode);

    ProcessResponse signProcess(VnptProcessDto process);

    ProcessResponse signProcessForAdmin(VnptProcessDto process);

    VnptDocumentDto getVnptEContractByDocumentId(String documentId);

    void terminateContract(UUID contractId, String reason, UUID terminatedBy);
}