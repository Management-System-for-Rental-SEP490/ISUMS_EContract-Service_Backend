package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CoTenantDto;
import com.isums.contractservice.domains.dtos.CoTenantResponseDto;
import com.isums.contractservice.domains.entities.ContractCoTenant;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.ContractCoTenantService;
import com.isums.contractservice.infrastructures.repositories.ContractCoTenantRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractCoTenantServiceImpl implements ContractCoTenantService {

    private final ContractCoTenantRepository repo;
    private final EContractRepository contractRepo;
    private final ContractAccessPolicy accessPolicy;

    @Override
    @Transactional(readOnly = true)
    public List<CoTenantResponseDto> list(UUID contractId) {
        requireReadAccess(contractId);
        return repo.findByContractId(contractId).stream()
                .map(ContractCoTenantServiceImpl::toDto)
                .toList();
    }

    @Override
    @Transactional
    public CoTenantResponseDto create(UUID contractId, CoTenantDto req) {
        requireWriteAccess(contractId);
        ContractCoTenant saved = repo.save(fromDto(contractId, null, req));
        log.info("[CoTenant] created contractId={} coTenantId={} name={}",
                contractId, saved.getId(), saved.getFullName());
        return toDto(saved);
    }

    @Override
    @Transactional
    public CoTenantResponseDto update(UUID contractId, UUID coTenantId, CoTenantDto req) {
        requireWriteAccess(contractId);
        ContractCoTenant existing = repo.findById(coTenantId)
                .orElseThrow(() -> new NotFoundException("Co-tenant not found: " + coTenantId));
        if (!existing.getContractId().equals(contractId)) {
            throw new NotFoundException("Co-tenant " + coTenantId + " không thuộc contract " + contractId);
        }
        existing.setFullName(req.fullName());
        existing.setIdentityNumber(req.identityNumber());
        existing.setIdentityType(req.identityType());
        existing.setDateOfBirth(req.dateOfBirth());
        existing.setGender(req.gender());
        existing.setNationality(req.nationality());
        existing.setRelationship(req.relationship());
        existing.setPhoneNumber(req.phoneNumber());
        repo.save(existing);
        log.info("[CoTenant] updated coTenantId={}", coTenantId);
        return toDto(existing);
    }

    @Override
    @Transactional
    public void delete(UUID contractId, UUID coTenantId) {
        requireWriteAccess(contractId);
        ContractCoTenant existing = repo.findById(coTenantId)
                .orElseThrow(() -> new NotFoundException("Co-tenant not found: " + coTenantId));
        if (!existing.getContractId().equals(contractId)) {
            throw new NotFoundException("Co-tenant " + coTenantId + " không thuộc contract " + contractId);
        }
        repo.delete(existing);
        log.info("[CoTenant] deleted coTenantId={}", coTenantId);
    }

    @Override
    @Transactional
    public List<CoTenantResponseDto> replaceAll(UUID contractId, List<CoTenantDto> coTenants) {
        requireWriteAccess(contractId);
        repo.deleteByContractId(contractId);
        if (coTenants == null || coTenants.isEmpty()) return List.of();
        List<ContractCoTenant> entities = coTenants.stream()
                .map(dto -> fromDto(contractId, null, dto))
                .toList();
        return repo.saveAll(entities).stream()
                .map(ContractCoTenantServiceImpl::toDto)
                .toList();
    }

    private void requireReadAccess(UUID contractId) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        accessPolicy.requireReadAccess(contract);
    }

    private void requireWriteAccess(UUID contractId) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        accessPolicy.requireWriteAccess(contract);
    }

    private static ContractCoTenant fromDto(UUID contractId, UUID id, CoTenantDto dto) {
        return ContractCoTenant.builder()
                .id(id)
                .contractId(contractId)
                .fullName(dto.fullName())
                .identityNumber(dto.identityNumber())
                .identityType(dto.identityType())
                .dateOfBirth(dto.dateOfBirth())
                .gender(dto.gender())
                .nationality(dto.nationality())
                .relationship(dto.relationship())
                .phoneNumber(dto.phoneNumber())
                .build();
    }

    private static CoTenantResponseDto toDto(ContractCoTenant e) {
        return CoTenantResponseDto.builder()
                .id(e.getId())
                .contractId(e.getContractId())
                .fullName(e.getFullName())
                .identityNumber(e.getIdentityNumber())
                .identityType(e.getIdentityType())
                .dateOfBirth(e.getDateOfBirth())
                .gender(e.getGender())
                .nationality(e.getNationality())
                .relationship(e.getRelationship())
                .phoneNumber(e.getPhoneNumber())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
