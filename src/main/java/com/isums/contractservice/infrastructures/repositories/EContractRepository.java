package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.EContract;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EContractRepository extends JpaRepository<EContract, UUID>, JpaSpecificationExecutor<EContract> {

    Optional<EContract> findByDocumentId(String documentId);

    Optional<EContract> findByDocumentNo(String documentNo);

    List<EContract> findAllByOrderByCreatedAtAsc();

    Optional<EContract> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<EContract> findByHouseIdAndUserId(UUID houseId, UUID userId);

    List<EContract> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
