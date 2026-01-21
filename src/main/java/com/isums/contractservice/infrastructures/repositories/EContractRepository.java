package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.EContract;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EContractRepository extends JpaRepository<EContract, String> {
}
