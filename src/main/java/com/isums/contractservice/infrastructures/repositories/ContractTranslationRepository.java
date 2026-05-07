package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.ContractTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractTranslationRepository extends JpaRepository<ContractTranslation, UUID> {

    Optional<ContractTranslation> findBySourceHashAndSourceLanguageAndTargetLanguage(
            String sourceHash, String sourceLanguage, String targetLanguage);

    @Modifying
    @Query("UPDATE ContractTranslation c SET c.hitCount = c.hitCount + 1 WHERE c.id = :id")
    void incrementHit(@Param("id") UUID id);
}
