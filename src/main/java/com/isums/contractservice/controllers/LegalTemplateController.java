package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.domains.dtos.CreateLegalTemplateRequest;
import com.isums.contractservice.domains.dtos.LegalTemplateDto;
import com.isums.contractservice.infrastructures.abstracts.LegalTemplateService;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.userservice.grpc.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin-side CRUD for the legal_template registry. Read endpoints are open to
 * MANAGER as well so they can preview the text that tenants will see; mutating
 * endpoints are restricted to LANDLORD because changing legal text is a
 * landlord-only governance action.
 */
@RestController
@RequestMapping("/api/econtracts/legal-templates")
@RequiredArgsConstructor
public class LegalTemplateController {

    private final LegalTemplateService legalTemplateService;
    private final UserGrpcClient userGrpc;

    @GetMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<List<LegalTemplateDto>> listActive() {
        return ApiResponses.ok(legalTemplateService.listActive(), "Success");
    }

    @GetMapping("/{key}/history")
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<List<LegalTemplateDto>> history(@PathVariable("key") String key) {
        return ApiResponses.ok(legalTemplateService.getHistory(key), "Success");
    }

    @PostMapping
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<LegalTemplateDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateLegalTemplateRequest request) {
        return ApiResponses.created(
                legalTemplateService.create(internalActorId(jwt), request),
                "Legal template version created");
    }

    @PatchMapping("/{id}/expire")
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<LegalTemplateDto> expire(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {
        return ApiResponses.ok(
                legalTemplateService.expire(id, internalActorId(jwt)),
                "Legal template expired");
    }

    private UUID internalActorId(Jwt jwt) {
        UUID keycloakId;
        try {
            keycloakId = UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT subject");
        }
        UserResponse user = userGrpc.getUserIdAndRoleByKeyCloakId(keycloakId.toString());
        return UUID.fromString(user.getId());
    }
}
