package com.isums.contractservice.controllers;

import com.isums.contractservice.domains.dtos.ApiResponse;
import com.isums.contractservice.domains.dtos.ApiResponses;
import com.isums.contractservice.domains.dtos.CoTenantDto;
import com.isums.contractservice.domains.dtos.CoTenantResponseDto;
import com.isums.contractservice.infrastructures.abstracts.ContractCoTenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/econtracts/{contractId}/co-tenants")
@RequiredArgsConstructor
@Tag(name = "Co-tenants", description = "Co-tenant management (Residence Law 2020 — temporary residence registration)")
public class ContractCoTenantController {

    private final ContractCoTenantService service;

    @Operation(summary = "List co-tenants of a contract")
    @GetMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER','TECHNICAL_STAFF','TENANT')")
    public ApiResponse<List<CoTenantResponseDto>> list(
            @Parameter(description = "Contract ID", required = true) @PathVariable UUID contractId) {
        return ApiResponses.ok(service.list(contractId), "Success");
    }

    @Operation(summary = "Add a co-tenant")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<CoTenantResponseDto> create(
            @PathVariable UUID contractId,
            @RequestBody @Valid CoTenantDto req) {
        return ApiResponses.ok(service.create(contractId, req), "Co-tenant created");
    }

    @Operation(summary = "Update a co-tenant")
    @PutMapping("/{coTenantId}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<CoTenantResponseDto> update(
            @PathVariable UUID contractId,
            @PathVariable UUID coTenantId,
            @RequestBody @Valid CoTenantDto req) {
        return ApiResponses.ok(service.update(contractId, coTenantId, req), "Co-tenant updated");
    }

    @Operation(summary = "Remove a co-tenant")
    @DeleteMapping("/{coTenantId}")
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<Void> delete(
            @PathVariable UUID contractId,
            @PathVariable UUID coTenantId) {
        service.delete(contractId, coTenantId);
        return ApiResponses.ok(null, "Co-tenant deleted");
    }

    @Operation(
            summary = "Replace the entire co-tenants list (bulk replace)",
            description = "Delete all existing co-tenants of the contract then insert the new list. Used when the FE submits the full array."
    )
    @PutMapping
    @PreAuthorize("hasAnyRole('LANDLORD','MANAGER')")
    public ApiResponse<List<CoTenantResponseDto>> replaceAll(
            @PathVariable UUID contractId,
            @RequestBody @Valid List<@Valid CoTenantDto> coTenants) {
        return ApiResponses.ok(service.replaceAll(contractId, coTenants), "Co-tenants replaced");
    }
}

