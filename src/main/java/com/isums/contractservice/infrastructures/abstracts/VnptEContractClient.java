package com.isums.contractservice.infrastructures.abstracts;


import com.isums.contractservice.domains.dtos.*;

import java.util.List;

public interface VnptEContractClient {

    VnptResult<VnptDocumentDto> createDocument(String token, CreateDocumentDto create);
    String getToken();
    VnptResult<List<VnptUserDto>> CreateOrUpdateUser(String token, VnptUserUpsert user);
    VnptResult<ProcessLoginInfoDto> getAccessInfoByProcessCode (String processCode);
}
