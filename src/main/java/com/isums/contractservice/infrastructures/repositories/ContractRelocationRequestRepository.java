package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.ContractRelocationRequest;
import com.isums.contractservice.domains.enums.RelocationRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractRelocationRequestRepository extends JpaRepository<ContractRelocationRequest, UUID> {

    boolean existsByOldContractIdAndStatusIn(UUID oldContractId, Collection<RelocationRequestStatus> statuses);

    List<ContractRelocationRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<ContractRelocationRequest> findAllByOrderByCreatedAtDesc();

    Optional<ContractRelocationRequest> findByNewContractId(UUID newContractId);

    Optional<ContractRelocationRequest> findFirstByOldContractIdAndStatusInOrderByCreatedAtDesc(
            UUID oldContractId,
            Collection<RelocationRequestStatus> statuses);

    Optional<ContractRelocationRequest> findFirstByOldContractIdOrderByCreatedAtDesc(UUID oldContractId);
}
