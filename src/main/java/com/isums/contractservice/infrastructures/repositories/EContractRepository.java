package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.EContract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EContractRepository extends JpaRepository<EContract, UUID> {
}
