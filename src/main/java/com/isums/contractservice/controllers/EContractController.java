package com.isums.contractservice.controllers;

import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import jakarta.validation.Valid;
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
    private final VnptEContractClient client;

    @PostMapping
    public ApiResponse<EContractDto> createDocument(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateEContractRequest req) {
        UUID actorId = extractActorId(jwt);
        EContractDto res = contractService.createDraftEContract(actorId, req);
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

    @PutMapping("/{id}")
    public ApiResponse<EContractDto> updateEContractById(@PathVariable UUID id, @Valid @RequestBody UpdateEContractRequest req) {
        EContractDto res = contractService.updateEContractById(id, req);
        return ApiResponses.ok(res, "Success to update e-contract");
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

    @PutMapping("/confirm/{id}")
    public ApiResponse<VnptDocumentDto> confirmEContract(@PathVariable UUID id) {
        var res = contractService.confirmEContract(id);
        return ApiResponses.ok(res, "Success to confirm e-contract");
    }

    // Test VNPT EContract API
    @GetMapping("/token")
    public String getToken() {
        return client.getToken();
    }

    @PostMapping("/processCode")
    public ApiResponse<ProcessLoginInfoDto> processCode(@RequestBody ProcessCodeLoginRequest req) {
        ProcessLoginInfoDto res = contractService.getAccessInfoByProcessCode(req.processCode());
        return ApiResponses.ok(res, "Success to get access info from VNPT");
    }

//    @PostMapping("/ready")
//    public ApiResponse<VnptDocumentDto> readyEContract(@RequestBody ReadyEContractRequest req) {
//        VnptDocumentDto res = contractService.readyEContract(req);
//        return ApiResponses.ok(res, "Success to ready e-contract");
//    }

    @PostMapping("/outsystem")
    public ApiResponse<EContractDto> getEContractByDocumentId(@RequestBody ProcessCodeLoginRequest req) {
        EContractDto res = contractService.getEContractOutSystem(req.processCode());
        return ApiResponses.ok(res, "Success to get e-contract outsystem");
    }

    @PostMapping("/sign")
    public ApiResponse<ProcessResponse> signEContract(@RequestBody VnptProcessDto req) {
        ProcessResponse res = contractService.signProcess(req);
        return ApiResponses.ok(res, "Success to sign e-contract");
    }

    @PostMapping("/sign-admin")
    public ApiResponse<ProcessResponse> signEContractAdmin(@RequestBody VnptProcessDto req) {
        ProcessResponse res = contractService.signProcessForAdmin(req);
        return ApiResponses.ok(res, "Success to sign e-contract");
    }
}
