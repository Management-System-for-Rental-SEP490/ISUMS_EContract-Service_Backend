package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.LandlordProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandlordProfileRepository extends JpaRepository<LandlordProfile, UUID> {
    Optional<LandlordProfile> findByUserId(UUID userId);
}