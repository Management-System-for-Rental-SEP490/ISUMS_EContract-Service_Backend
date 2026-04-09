package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.RenewalRequest;
import com.isums.contractservice.domains.enums.RenewalRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenewalRequestRepository extends JpaRepository<RenewalRequest, UUID> {

    Optional<RenewalRequest> findByContractIdAndStatusNotIn(UUID contractId, List<RenewalRequestStatus> excludedStatuses);

    boolean existsByContractIdAndStatusNotIn(UUID contractId, List<RenewalRequestStatus> excludedStatuses);

    Optional<RenewalRequest> findByHouseIdAndStatusNotIn(UUID houseId, List<RenewalRequestStatus> excludedStatuses);

    Optional<RenewalRequest> findByNewContractId(UUID eContractId);
}