package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.LandlordProfileDto;
import com.isums.contractservice.domains.dtos.UpsertLandlordProfileRequest;
import com.isums.contractservice.domains.entities.LandlordProfile;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.LandlordProfileService;
import com.isums.contractservice.infrastructures.repositories.LandlordProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LandlordProfileServiceImpl implements LandlordProfileService {

    private final LandlordProfileRepository repository;

    @Override
    @Transactional
    public LandlordProfileDto upsert(UUID userId, UpsertLandlordProfileRequest req) {
        LandlordProfile profile = repository.findByUserId(userId)
                .orElse(LandlordProfile.builder().userId(userId).build());

        profile.setFullName(req.fullName());
        profile.setIdentityNumber(req.identityNumber());
        profile.setIdentityIssueDate(req.identityIssueDate());
        profile.setIdentityIssuePlace(req.identityIssuePlace());
        profile.setAddress(req.address());
        profile.setPhoneNumber(req.phoneNumber());
        profile.setEmail(req.email());
        profile.setBankAccount(req.bankAccount());

        repository.save(profile);
        log.info("[LandlordProfile] Upserted userId={}", userId);
        return LandlordProfileDto.from(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public LandlordProfileDto getByUserId(UUID userId) {
        return repository.findByUserId(userId)
                .map(LandlordProfileDto::from)
                .orElseThrow(() -> new NotFoundException(
                        "Landlord profile not found for userId: " + userId));
    }
}