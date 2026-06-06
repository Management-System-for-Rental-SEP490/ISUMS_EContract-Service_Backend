package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.ContractDemoSession;
import com.isums.contractservice.domains.enums.ContractDemoStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ContractDemoSessionRepository extends JpaRepository<ContractDemoSession, UUID> {
    Optional<ContractDemoSession> findFirstByContractIdAndStatusOrderByStartedAtDesc(
            UUID contractId,
            ContractDemoStatus status);
}
