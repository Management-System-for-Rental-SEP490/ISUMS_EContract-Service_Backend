package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.EContractTemplate;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EContractTemplateRepository extends JpaRepository<EContractTemplate, UUID> {

//    @Cacheable("LeaseEContract")
    Optional<EContractTemplate> findByCode(String code);

    boolean existsByCode(String code);
}
