package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.ContractInspection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ContractInspectionRepository extends JpaRepository<ContractInspection, UUID> {
}
