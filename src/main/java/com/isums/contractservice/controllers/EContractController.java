package com.isums.contractservice.controllers;

import com.isums.contractservice.abstracts.EContractService;
import com.isums.contractservice.domains.dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts")
@RequiredArgsConstructor
public class EContractController {
    private final EContractService contractService;

    @PostMapping
    public ApiResponse<VnptDocumentDto> createDocument(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateEContractRequest req) {
        UUID actorId = extractActorId(jwt);
        VnptDocumentDto res = contractService.CreateDraftVnptEContract(actorId, req);
        return ApiResponses.created(res, "Success to create e-contract");
    }

    private UUID extractActorId(Jwt jwt) {
        String raw = jwt.getSubject();

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Missing user id in JWT (claim user_id or sub)");
        }

        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT user id is not a UUID: " + raw);
        }
    }
}
