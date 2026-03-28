package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.*;
import com.isums.contractservice.infrastructures.abstracts.EContractService;
import com.isums.contractservice.infrastructures.abstracts.VnptEContractClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts")
@RequiredArgsConstructor
public class EContractController {

    private final EContractService contractService;
    private final VnptEContractClient client;

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','LANDLORD')")
    public ApiResponse<EContractDto> createDocument(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CreateEContractRequest req) {
        UUID actorId = extractActorId(jwt);
        return ApiResponses.created(
                contractService.createDraftEContract(actorId, jwt.getTokenValue(), req),
                "Success to create e-contract");
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','LANDLORD')")
    public ApiResponse<EContractDto> getEContractById(@PathVariable UUID id) {
        return ApiResponses.ok(contractService.getEContractById(id),
                "Success to get e-contract");
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','LANDLORD')")
    public ApiResponse<List<EContractDto>> getAllEContracts() {
        return ApiResponses.ok(contractService.getAllEContracts(), "Success to get e-contracts");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','LANDLORD')")
    public ApiResponse<EContractDto> updateEContractById(@PathVariable UUID id, @Valid @RequestBody UpdateEContractRequest req) {
        return ApiResponses.ok(contractService.updateEContractById(id, req), "Success to update e-contract");
    }

    @GetMapping("/{id}/cccd-status")
    public ApiResponse<Boolean> checkCccd(@PathVariable UUID id) {
        return ApiResponses.ok(contractService.hasCccd(id), "CCCD status checked");
    }

    @PutMapping(value = "/{id}/cccd", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Void> uploadCccd(@PathVariable String id, @RequestParam("frontImage") MultipartFile frontImage, @RequestParam("backImage") MultipartFile backImage) {
        contractService.uploadCccd(id, frontImage, backImage);
        return ApiResponses.ok(null, "CCCD uploaded successfully");
    }

    @PutMapping("/ready/{id}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<VnptDocumentDto> readyEContract(@PathVariable UUID id) {
        return ApiResponses.ok(contractService.readyEContract(id),
                "Success to ready e-contract");
    }

    @PutMapping("/confirm-by-admin/{id}")
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<Void> confirmByAdminEContract(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        contractService.confirmEContract(id, jwt.getSubject(), jwt.getTokenValue());
        return ApiResponses.ok(null, "Success to confirm e-contract");
    }

    @PostMapping("/sign-admin")
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<ProcessResponse> signEContractAdmin(@RequestBody VnptProcessDto req) {
        return ApiResponses.ok(contractService.signProcessForAdmin(req),
                "Success to sign e-contract");
    }

    @PostMapping("/processCode")
    public ApiResponse<ProcessLoginInfoDto> processCode(@RequestBody ProcessCodeLoginRequest req) {
        return ApiResponses.ok(
                contractService.getAccessInfoByProcessCode(req.processCode()),
                "Success to get access info from VNPT");
    }

    @PostMapping("/sign")
    public ApiResponse<ProcessResponse> signEContract(@RequestBody VnptProcessDto req) {
        return ApiResponses.ok(contractService.signProcess(req),
                "Success to sign e-contract");
    }

    @PutMapping("/{id}/terminate")
    @PreAuthorize("hasRole('LANDLORD')")
    public ApiResponse<Void> terminateContract(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody @Valid TerminateContractRequest req) {
        contractService.terminateContract(id, req.reason(), extractActorId(jwt));
        return ApiResponses.ok(null, "Contract terminated successfully");
    }

    @PostMapping("/outsystem")
    public ApiResponse<EContractDto> getEContractByDocumentId(@RequestBody ProcessCodeLoginRequest req) {
        return ApiResponses.ok(contractService.getEContractOutSystem(req.processCode()),
                "Success to get e-contract outsystem");
    }

    @GetMapping("/vnpt-document/{documentId}")
    public ApiResponse<VnptDocumentDto> getVnptEContractByDocumentId(@PathVariable String documentId) {
        return ApiResponses.ok(
                contractService.getVnptEContractByDocumentId(documentId),
                "Success to get e-contract");
    }

    @GetMapping("/test-token")
    public String getToken() {
        return client.getToken();
    }

    private UUID extractActorId(Jwt jwt) {
        String raw = jwt.getSubject();
        if (raw == null || raw.isBlank())
            throw new IllegalStateException("Missing user id in JWT");
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT user id is not a UUID: " + raw);
        }
    }
}