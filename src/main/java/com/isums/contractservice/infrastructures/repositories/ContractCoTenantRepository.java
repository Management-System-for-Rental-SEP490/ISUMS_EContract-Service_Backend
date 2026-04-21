package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.ContractCoTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContractCoTenantRepository extends JpaRepository<ContractCoTenant, UUID> {

    List<ContractCoTenant> findByContractId(UUID contractId);

    void deleteByContractId(UUID contractId);
}
