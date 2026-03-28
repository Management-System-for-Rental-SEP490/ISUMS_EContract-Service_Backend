package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.LandlordProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts/landlord-profiles")
@RequiredArgsConstructor
public class LandlordProfileController {

    private final LandlordProfileService service;

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<LandlordProfileDto> getMe(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ApiResponses.ok(service.getByUserId(userId), "Success");
    }

    @PutMapping("/me")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<LandlordProfileDto> upsertMe(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid UpsertLandlordProfileRequest req) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ApiResponses.ok(service.upsert(userId, req), "Profile updated successfully");
    }
}