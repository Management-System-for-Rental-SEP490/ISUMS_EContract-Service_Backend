package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface EContractService {

    EContractDto createDraft(UUID actorId, String jwtToken, CreateEContractRequest req);

    EContractDto getById(UUID id);

    List<EContractDto> getAll();

    EContractDto updateContract(UUID id, UpdateEContractRequest req);

    EContractDto confirmByAdmin(UUID contractId, UUID actorId);

    ProcessResponse signByLandlord(VnptProcessDto process);

    void cancelByLandlord(UUID contractId, String reason, UUID actorId);

    String getPdfPresignedUrl(UUID contractId, String contractToken);

    VnptDocumentDto tenantConfirmWithCccd(UUID contractId, MultipartFile frontImage, MultipartFile backImage, String contractToken);

    ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode);

    ProcessResponse signByTenant(VnptProcessDto process);

    void cancelByTenant(UUID contractId, String reason, UUID tenantUserId, String contractToken);

    void deleteContract(UUID contractId, UUID actorId);

    boolean hasCccd(UUID contractId);

    EContractDto getOutSystem(String processCode);

    VnptDocumentDto getVnptDocumentById(String documentId);
}