package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.CoTenantDto;
import com.isums.contractservice.domains.dtos.CoTenantResponseDto;

import java.util.List;
import java.util.UUID;

public interface ContractCoTenantService {

    List<CoTenantResponseDto> list(UUID contractId);

    CoTenantResponseDto create(UUID contractId, CoTenantDto req);

    CoTenantResponseDto update(UUID contractId, UUID coTenantId, CoTenantDto req);

    void delete(UUID contractId, UUID coTenantId);

    List<CoTenantResponseDto> replaceAll(UUID contractId, List<CoTenantDto> coTenants);
}
