package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.domains.dtos.ContractDemoPreview;
import com.isums.contractservice.domains.dtos.ContractDemoRequest;
import com.isums.contractservice.domains.enums.ContractDemoScenario;
import com.isums.contractservice.infrastructures.abstracts.ContractDemoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts/demo")
@RequiredArgsConstructor
@PreAuthorize("hasRole('LANDLORD')")
public class ContractDemoController {

    private final ContractDemoService demoService;

    @GetMapping("/preview")
    public ApiResponse<ContractDemoPreview> preview(
            @RequestParam UUID contractId,
            @RequestParam ContractDemoScenario scenario,
            @RequestParam(required = false) Instant customEffectiveAt) {
        return ApiResponses.ok(
                demoService.preview(contractId, scenario, customEffectiveAt),
                "Demo preview generated");
    }

    @PostMapping("/run")
    public ApiResponse<ContractDemoPreview> run(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid ContractDemoRequest request) {
        return ApiResponses.ok(
                demoService.run(request, actorId(jwt)),
                "Contract demo started");
    }

    @GetMapping("/active")
    public ApiResponse<ContractDemoPreview> active(@RequestParam UUID contractId) {
        return ApiResponses.ok(demoService.getActive(contractId), "Active demo found");
    }

    @DeleteMapping("/active")
    public ApiResponse<Void> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID contractId) {
        demoService.cancel(contractId, actorId(jwt));
        return ApiResponses.ok(null, "Demo clock stopped; business changes were not rolled back");
    }

    private UUID actorId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid JWT subject");
        }
    }
}
