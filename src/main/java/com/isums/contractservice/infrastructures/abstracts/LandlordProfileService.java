package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.LandlordProfileDto;
import com.isums.contractservice.domains.dtos.UpsertLandlordProfileRequest;

import java.util.UUID;

public interface LandlordProfileService {
    LandlordProfileDto upsert(UUID userId, UpsertLandlordProfileRequest req);
    LandlordProfileDto getByUserId(UUID userId);
}