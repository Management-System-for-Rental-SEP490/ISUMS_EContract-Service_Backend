package com.isums.contractservice.controllers;

import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.domains.dtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts")
@RequiredArgsConstructor
public class EContractController {
    private final EContractService contractService;

    @PostMapping
    public ApiResponse<EContractDto> createDocument(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateEContractRequest req) {
        UUID actorId = extractActorId(jwt);
        EContractDto res = contractService.CreateDraftEContract(actorId, req);
        return ApiResponses.created(res, "Success to create e-contract");
    }

    @GetMapping("{id}")
    public ApiResponse<EContractDto> getEContractById(@PathVariable UUID id) {
        EContractDto res = contractService.getEContractById(id);
        return ApiResponses.ok(res, "Success to get e-contract");
    }

    @GetMapping
    public ApiResponse<List<EContractDto>> getAllEContracts(@AuthenticationPrincipal Jwt jwt) {
        List<EContractDto> res = contractService.getAllEContracts();
        return ApiResponses.ok(res, "Success to get e-contracts");
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
