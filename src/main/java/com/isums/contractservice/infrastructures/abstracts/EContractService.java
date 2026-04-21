package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.*;
import common.paginations.dtos.PageRequest;
import common.paginations.dtos.PageResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface EContractService {

    EContractDto createDraft(UUID actorId, String jwtToken, CreateEContractRequest req);

    EContractDto getById(UUID id, Authentication auth);

    PageResponse<EContractDto> getAll(PageRequest request, Authentication auth);

    EContractDto updateContract(UUID id, UpdateEContractRequest req);

    EContractDto confirmByAdmin(UUID contractId, UUID actorId);

    ProcessResponse signByLandlord(VnptProcessDto process);

    void cancelByLandlord(UUID contractId, String reason, UUID actorId);

    String getPdfPresignedUrl(UUID contractId, String contractToken);

    VnptDocumentDto tenantConfirmWithCccd(UUID contractId, MultipartFile frontImage, MultipartFile backImage, String contractToken);

    VnptDocumentDto tenantConfirmWithPassport(UUID contractId, MultipartFile passportImage, String contractToken);

    boolean hasPassport(UUID contractId);

    void triggerReadyForLandlordSignatureNotification(UUID contractId);

    ProcessLoginInfoDto getAccessInfoByProcessCode(String processCode);

    ProcessResponse signByTenant(VnptProcessDto process);

    void cancelByTenant(UUID contractId, String reason, UUID tenantUserId, String contractToken);

    void deleteContract(UUID contractId, UUID actorId);

    boolean hasCccd(UUID contractId);

    EContractDto getOutSystem(String processCode);

    VnptDocumentDto getVnptDocumentById(String documentId);

    List<TenantEContractDto> getMyContracts(UUID keycloakId);

    String getPdfUrlForTenant(UUID contractId, UUID keycloakId);

    void confirmRefund(UUID contractId, ConfirmRefundRequest req);

    EContractDto cloneForRenewal(UUID oldContractId, CloneForRenewalRequest req, UUID actorId, String jwtToken);
}
