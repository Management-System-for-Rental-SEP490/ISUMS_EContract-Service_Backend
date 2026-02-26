package com.isums.contractservice.infrastructures.abstracts;


import com.isums.contractservice.domains.dtos.*;

import java.util.List;

public interface VnptEContractClient {

    VnptResult<VnptDocumentDto> createDocument(String token, CreateDocumentDto create);

    String getToken();

    VnptResult<List<VnptUserDto>> CreateOrUpdateUser(String token, VnptUserUpsert user);

    String getAccessInfoByProcessCode(String processCode);

    VnptResult<VnptDocumentDto> UpdateProcess(String token, VnptUpdateProcessDTO update);

    VnptResult<VnptDocumentDto> sendProcess(String token, String documentId);

    VnptResult<ProcessResponse> signProcess(VnptProcessDto process);
}
